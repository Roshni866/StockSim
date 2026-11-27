-- V2: per-user simulation, watchlist, scenarios, backtesting. Additive only.
CREATE TABLE IF NOT EXISTS app_meta (
    meta_key   VARCHAR(50)  PRIMARY KEY,
    meta_value VARCHAR(255) NOT NULL
) ENGINE=InnoDB;

ALTER TABLE users ADD COLUMN starting_capital DECIMAL(15,2) NOT NULL DEFAULT 100000.00;
ALTER TABLE stocks ADD COLUMN beta DECIMAL(4,2) NOT NULL DEFAULT 1.00;
UPDATE stocks SET beta = CASE symbol
    WHEN 'TCS' THEN 0.85 WHEN 'INFY' THEN 0.95 WHEN 'RELIANCE' THEN 1.05 WHEN 'HDFCBANK' THEN 1.00
    WHEN 'ITC' THEN 0.70 WHEN 'WIPRO' THEN 0.90 WHEN 'ICICIBANK' THEN 1.20 WHEN 'TATAMOTORS' THEN 1.45
    WHEN 'SBIN' THEN 1.30 WHEN 'HINDUNILVR' THEN 0.60 ELSE 1.00 END;

ALTER TABLE holdings ADD COLUMN cost_basis DECIMAL(18,2) NOT NULL DEFAULT 0.00;
UPDATE holdings SET cost_basis = quantity * average_buy_price WHERE cost_basis = 0;

ALTER TABLE transactions ADD COLUMN realized_pl DECIMAL(15,2) NULL;

ALTER TABLE alerts ADD COLUMN triggered_day INT NULL;
ALTER TABLE alerts ADD COLUMN triggered_price DECIMAL(12,2) NULL;
ALTER TABLE alerts ADD COLUMN created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;

-- Percentage price shock a scripted event applies to its sector on its day.
ALTER TABLE market_events ADD COLUMN price_impact_pct DECIMAL(6,2) NOT NULL DEFAULT 0.00;
UPDATE market_events SET price_impact_pct = CASE
    WHEN impact = 'Positive' AND sector = 'ALL' THEN 1.80
    WHEN impact = 'Negative' AND sector = 'ALL' THEN -2.50
    WHEN impact = 'Positive' THEN 1.50
    WHEN impact = 'Negative' THEN -1.50
    ELSE 0.00 END;

CREATE TABLE IF NOT EXISTS user_settings (
    user_id        INT PRIMARY KEY,
    confirm_trades BOOLEAN      NOT NULL DEFAULT TRUE,
    export_dir     VARCHAR(500) NULL,
    seen_alert_day INT          NOT NULL DEFAULT 0,
    CONSTRAINT fk_settings_user FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS watchlist (
    user_id  INT       NOT NULL,
    stock_id INT       NOT NULL,
    added_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, stock_id),
    CONSTRAINT fk_watch_user  FOREIGN KEY (user_id)  REFERENCES users(user_id)   ON DELETE CASCADE,
    CONSTRAINT fk_watch_stock FOREIGN KEY (stock_id) REFERENCES stocks(stock_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS market_scenarios (
    scenario_id   INT PRIMARY KEY,
    code          VARCHAR(30)  NOT NULL UNIQUE,
    title         VARCHAR(100) NOT NULL,
    description   VARCHAR(500) NOT NULL,
    duration_days INT          NOT NULL,
    severity      VARCHAR(20)  NOT NULL,
    seed          BIGINT       NOT NULL,
    noise_pct     DECIMAL(6,3) NOT NULL
) ENGINE=InnoDB;

-- daily_pct applies on each scenario day from from_day to to_day inclusive.
-- MARKET rules are scaled by a stock's beta; SECTOR rules apply as written.
CREATE TABLE IF NOT EXISTS scenario_rules (
    rule_id     INT AUTO_INCREMENT PRIMARY KEY,
    scenario_id INT                     NOT NULL,
    scope       ENUM('MARKET','SECTOR') NOT NULL,
    sector      VARCHAR(50)             NULL,
    from_day    INT                     NOT NULL,
    to_day      INT                     NOT NULL,
    daily_pct   DECIMAL(7,3)            NOT NULL,
    headline    VARCHAR(200)            NOT NULL,
    UNIQUE KEY uq_rule (scenario_id, from_day, headline),
    CONSTRAINT fk_rule_scenario FOREIGN KEY (scenario_id) REFERENCES market_scenarios(scenario_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS scenario_runs (
    run_id                INT AUTO_INCREMENT PRIMARY KEY,
    user_id               INT           NOT NULL,
    scenario_id           INT           NOT NULL,
    seed                  BIGINT        NOT NULL,
    start_day             INT           NOT NULL,
    end_day               INT           NOT NULL,
    last_day              INT           NOT NULL,
    status                ENUM('ACTIVE','COMPLETED','CANCELLED') NOT NULL DEFAULT 'ACTIVE',
    start_portfolio_value DECIMAL(18,2) NOT NULL,
    end_portfolio_value   DECIMAL(18,2) NULL,
    start_transaction_id  INT           NOT NULL DEFAULT 0,
    end_transaction_id    INT           NULL,
    created_at            TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_run_user (user_id),
    CONSTRAINT fk_run_user     FOREIGN KEY (user_id)     REFERENCES users(user_id) ON DELETE CASCADE,
    CONSTRAINT fk_run_scenario FOREIGN KEY (scenario_id) REFERENCES market_scenarios(scenario_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS scenario_run_results (
    run_id      INT           NOT NULL,
    stock_id    INT           NOT NULL,
    start_price DECIMAL(12,2) NOT NULL,
    end_price   DECIMAL(12,2) NOT NULL,
    PRIMARY KEY (run_id, stock_id),
    CONSTRAINT fk_result_run   FOREIGN KEY (run_id)   REFERENCES scenario_runs(run_id) ON DELETE CASCADE,
    CONSTRAINT fk_result_stock FOREIGN KEY (stock_id) REFERENCES stocks(stock_id)      ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS simulation_state (
    user_id       INT PRIMARY KEY,
    start_day     INT       NOT NULL,
    current_day   INT       NOT NULL,
    active_run_id INT       NULL,
    updated_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_state_user FOREIGN KEY (user_id)       REFERENCES users(user_id)        ON DELETE CASCADE,
    CONSTRAINT fk_state_run  FOREIGN KEY (active_run_id) REFERENCES scenario_runs(run_id) ON DELETE SET NULL
) ENGINE=InnoDB;

INSERT IGNORE INTO simulation_state (user_id, start_day, current_day)
SELECT user_id, 1, sim_day FROM users;

-- Each user's own price path. stock_history stays untouched reference data.
CREATE TABLE IF NOT EXISTS simulation_prices (
    user_id         INT           NOT NULL,
    stock_id        INT           NOT NULL,
    simulation_day  INT           NOT NULL,
    price           DECIMAL(12,2) NOT NULL,
    scenario_run_id INT           NULL,
    PRIMARY KEY (user_id, stock_id, simulation_day),
    CONSTRAINT fk_sp_user  FOREIGN KEY (user_id)  REFERENCES users(user_id)   ON DELETE CASCADE,
    CONSTRAINT fk_sp_stock FOREIGN KEY (stock_id) REFERENCES stocks(stock_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS simulation_events (
    sim_event_id    INT AUTO_INCREMENT PRIMARY KEY,
    user_id         INT          NOT NULL,
    simulation_day  INT          NOT NULL,
    title           VARCHAR(200) NOT NULL,
    description     VARCHAR(500) NOT NULL,
    sector          VARCHAR(50)  NOT NULL,
    impact_pct      DECIMAL(7,3) NOT NULL,
    source          ENUM('SCRIPTED','SCENARIO') NOT NULL,
    scenario_run_id INT          NULL,
    INDEX idx_simevent (user_id, simulation_day),
    CONSTRAINT fk_se_user FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS portfolio_snapshots (
    user_id        INT           NOT NULL,
    simulation_day INT           NOT NULL,
    cash           DECIMAL(18,2) NOT NULL,
    holdings_value DECIMAL(18,2) NOT NULL,
    total_value    DECIMAL(18,2) NOT NULL,
    PRIMARY KEY (user_id, simulation_day),
    CONSTRAINT fk_snap_user FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS backtest_runs (
    run_id          INT AUTO_INCREMENT PRIMARY KEY,
    user_id         INT           NOT NULL,
    stock_id        INT           NOT NULL,
    strategy        VARCHAR(30)   NOT NULL,
    short_period    INT           NOT NULL,
    long_period     INT           NOT NULL,
    lookback        INT           NOT NULL,
    entry_pct       DECIMAL(8,3)  NOT NULL,
    exit_pct        DECIMAL(8,3)  NOT NULL,
    initial_capital DECIMAL(18,2) NOT NULL,
    fee_pct         DECIMAL(6,3)  NOT NULL,
    from_day        INT           NOT NULL,
    to_day          INT           NOT NULL,
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_bt_user (user_id),
    CONSTRAINT fk_bt_user  FOREIGN KEY (user_id)  REFERENCES users(user_id)   ON DELETE CASCADE,
    CONSTRAINT fk_bt_stock FOREIGN KEY (stock_id) REFERENCES stocks(stock_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS backtest_trades (
    trade_id       INT AUTO_INCREMENT PRIMARY KEY,
    run_id         INT                NOT NULL,
    simulation_day INT                NOT NULL,
    trade_date     DATE               NOT NULL,
    side           ENUM('BUY','SELL') NOT NULL,
    quantity       INT                NOT NULL,
    price          DECIMAL(12,2)      NOT NULL,
    fee            DECIMAL(15,2)      NOT NULL,
    profit         DECIMAL(18,2)      NULL,
    note           VARCHAR(100)       NOT NULL,
    CONSTRAINT fk_btt_run FOREIGN KEY (run_id) REFERENCES backtest_runs(run_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS backtest_results (
    run_id               INT PRIMARY KEY,
    final_capital        DECIMAL(18,2) NOT NULL,
    total_return_pct     DECIMAL(14,2) NOT NULL,
    annualized_pct       DECIMAL(14,2) NULL,
    total_trades         INT           NOT NULL,
    winning_trades       INT           NOT NULL,
    losing_trades        INT           NOT NULL,
    win_rate_pct         DECIMAL(8,2)  NULL,
    max_drawdown_pct     DECIMAL(8,2)  NOT NULL,
    avg_trade_return_pct DECIMAL(14,2) NULL,
    benchmark_return_pct DECIMAL(14,2) NOT NULL,
    CONSTRAINT fk_btr_run FOREIGN KEY (run_id) REFERENCES backtest_runs(run_id) ON DELETE CASCADE
) ENGINE=InnoDB;

INSERT IGNORE INTO market_scenarios (scenario_id, code, title, description, duration_days, severity, seed, noise_pct) VALUES
 (1,'BULL','Bull Market','Hypothetical broad rally: steady buying across the market, strongest in high-beta shares.',30,'Moderate',1001,0.600),
 (2,'BEAR','Bear Market','Hypothetical prolonged decline with a brief relief rally midway.',30,'High',2002,0.700),
 (3,'CRASH','Market Crash','Hypothetical sharp sell-off over five days followed by a weak partial recovery.',15,'Severe',3003,1.200),
 (4,'IT_BOOM','IT Sector Boom','Hypothetical surge in technology demand lifting IT shares while the wider market is flat.',20,'Moderate',4004,0.500),
 (5,'BANK_DECLINE','Banking Sector Decline','Hypothetical asset-quality scare hitting banking shares with mild spill-over.',20,'High',5005,0.600),
 (6,'UNCERTAINTY','Economic Uncertainty','Hypothetical choppy market: alternating phases with defensive FMCG demand.',30,'Moderate',6006,1.500);

INSERT IGNORE INTO scenario_rules (scenario_id, scope, sector, from_day, to_day, daily_pct, headline) VALUES
 (1,'MARKET',NULL,1,30,0.450,'Broad-based buying lifts the market'),
 (1,'SECTOR','Auto',11,30,0.200,'Auto demand outlook improves'),
 (2,'MARKET',NULL,1,13,-0.600,'Selling pressure builds across sectors'),
 (2,'MARKET',NULL,14,18,0.400,'Short relief rally'),
 (2,'MARKET',NULL,19,30,-0.550,'Downtrend resumes'),
 (2,'SECTOR','FMCG',1,30,0.200,'Defensive buying cushions FMCG'),
 (3,'MARKET',NULL,1,5,-4.500,'Panic selling: market crashes'),
 (3,'SECTOR','Banking',1,5,-1.000,'Credit stress fears hit lenders'),
 (3,'MARKET',NULL,6,15,0.600,'Weak partial recovery'),
 (4,'SECTOR','IT',1,20,1.100,'Large technology contracts announced'),
 (4,'MARKET',NULL,1,20,0.050,'Wider market holds steady'),
 (5,'SECTOR','Banking',1,12,-1.300,'Asset-quality concerns hit banks'),
 (5,'SECTOR','Banking',13,20,-0.400,'Banking weakness lingers'),
 (5,'MARKET',NULL,1,20,-0.100,'Mild spill-over to the wider market'),
 (6,'MARKET',NULL,1,7,-0.700,'Growth worries weigh on shares'),
 (6,'MARKET',NULL,8,14,0.500,'Policy hopes spark a bounce'),
 (6,'MARKET',NULL,15,23,-0.600,'Fresh uncertainty returns'),
 (6,'MARKET',NULL,24,30,0.200,'Cautious stabilisation'),
 (6,'SECTOR','FMCG',1,30,0.250,'Investors favour defensive FMCG');
