-- V1: the original StockSim schema. Safe on an existing database:
-- nothing is dropped and seed rows are only added when missing.
CREATE TABLE IF NOT EXISTS users (
    user_id     INT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(100)  NOT NULL,
    username    VARCHAR(50)   NOT NULL UNIQUE,
    email       VARCHAR(100)  NOT NULL UNIQUE,
    password    VARCHAR(255)  NOT NULL,
    balance     DECIMAL(15,2) NOT NULL DEFAULT 100000.00,
    sim_day     INT           NOT NULL DEFAULT 1,
    created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS stocks (
    stock_id      INT AUTO_INCREMENT PRIMARY KEY,
    symbol        VARCHAR(20)   NOT NULL UNIQUE,
    company_name  VARCHAR(100)  NOT NULL,
    sector        VARCHAR(50)   NOT NULL,
    current_price DECIMAL(10,2) NOT NULL DEFAULT 0.00
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS stock_history (
    history_id     INT AUTO_INCREMENT PRIMARY KEY,
    stock_id       INT           NOT NULL,
    price          DECIMAL(10,2) NOT NULL,
    recorded_at    DATE          NOT NULL,
    simulation_day INT           NOT NULL,
    CONSTRAINT fk_history_stock FOREIGN KEY (stock_id) REFERENCES stocks(stock_id) ON DELETE CASCADE,
    UNIQUE KEY uq_stock_day (stock_id, simulation_day),
    INDEX idx_history_stock_day (stock_id, simulation_day)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS holdings (
    holding_id        INT AUTO_INCREMENT PRIMARY KEY,
    user_id           INT           NOT NULL,
    stock_id          INT           NOT NULL,
    quantity          INT           NOT NULL,
    average_buy_price DECIMAL(10,2) NOT NULL,
    CONSTRAINT fk_holding_user  FOREIGN KEY (user_id)  REFERENCES users(user_id)   ON DELETE CASCADE,
    CONSTRAINT fk_holding_stock FOREIGN KEY (stock_id) REFERENCES stocks(stock_id) ON DELETE CASCADE,
    UNIQUE KEY uq_user_stock (user_id, stock_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS transactions (
    transaction_id   INT AUTO_INCREMENT PRIMARY KEY,
    user_id          INT                 NOT NULL,
    stock_id         INT                 NOT NULL,
    transaction_type ENUM('BUY','SELL')  NOT NULL,
    quantity         INT                 NOT NULL,
    price            DECIMAL(10,2)       NOT NULL,
    total_amount     DECIMAL(15,2)       NOT NULL,
    transaction_date TIMESTAMP           NOT NULL DEFAULT CURRENT_TIMESTAMP,
    simulation_day   INT                 NOT NULL,
    CONSTRAINT fk_txn_user  FOREIGN KEY (user_id)  REFERENCES users(user_id)   ON DELETE CASCADE,
    CONSTRAINT fk_txn_stock FOREIGN KEY (stock_id) REFERENCES stocks(stock_id) ON DELETE CASCADE,
    INDEX idx_txn_user (user_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS goals (
    goal_id       INT AUTO_INCREMENT PRIMARY KEY,
    user_id       INT           NOT NULL,
    goal_name     VARCHAR(100)  NOT NULL,
    target_amount DECIMAL(15,2) NOT NULL,
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_goal_user FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS alerts (
    alert_id     INT AUTO_INCREMENT PRIMARY KEY,
    user_id      INT                   NOT NULL,
    stock_id     INT                   NOT NULL,
    `condition`  ENUM('ABOVE','BELOW') NOT NULL,
    target_price DECIMAL(10,2)         NOT NULL,
    triggered    BOOLEAN               NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_alert_user  FOREIGN KEY (user_id)  REFERENCES users(user_id)   ON DELETE CASCADE,
    CONSTRAINT fk_alert_stock FOREIGN KEY (stock_id) REFERENCES stocks(stock_id) ON DELETE CASCADE,
    INDEX idx_alert_user (user_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS market_events (
    event_id       INT AUTO_INCREMENT PRIMARY KEY,
    title          VARCHAR(200) NOT NULL,
    description    TEXT,
    sector         VARCHAR(50),
    simulation_day INT          NOT NULL,
    impact         VARCHAR(100),
    INDEX idx_event_day (simulation_day)
) ENGINE=InnoDB;

INSERT IGNORE INTO stocks (symbol, company_name, sector) VALUES
    ('TCS',        'Tata Consultancy Services', 'IT'),
    ('INFY',       'Infosys',                   'IT'),
    ('RELIANCE',   'Reliance Industries',       'Energy'),
    ('HDFCBANK',   'HDFC Bank',                 'Banking'),
    ('ITC',        'ITC Limited',               'FMCG'),
    ('WIPRO',      'Wipro',                     'IT'),
    ('ICICIBANK',  'ICICI Bank',                'Banking'),
    ('TATAMOTORS', 'Tata Motors',               'Auto'),
    ('SBIN',       'State Bank of India',       'Banking'),
    ('HINDUNILVR', 'Hindustan Unilever',        'FMCG');

-- Fictional events used by the simulator (not historical news).
-- simulation_day is counted from the day a user's simulation starts.
INSERT INTO market_events (title, description, sector, simulation_day, impact)
SELECT * FROM (
    SELECT 'IT sector reports strong quarterly growth' AS t, 'Large IT services firms beat revenue estimates on large deal wins.' AS d, 'IT' AS s, 5 AS n, 'Positive' AS i
    UNION ALL SELECT 'Central bank holds policy rate steady', 'Policy rates unchanged, easing pressure on lenders.', 'Banking', 12, 'Positive'
    UNION ALL SELECT 'Crude oil prices spike on supply concerns', 'Higher crude costs squeeze refining margins.', 'Energy', 20, 'Negative'
    UNION ALL SELECT 'Monsoon forecast above normal', 'A strong monsoon is expected to lift rural demand.', 'FMCG', 30, 'Positive'
    UNION ALL SELECT 'Auto sales slow on weak festive demand', 'Passenger vehicle dispatches fall month on month.', 'Auto', 45, 'Negative'
    UNION ALL SELECT 'Banking sector asset quality improves', 'Bad-loan ratios decline for a third straight quarter.', 'Banking', 60, 'Positive'
    UNION ALL SELECT 'Rupee weakens against the dollar', 'A weaker rupee lifts realisations for exporters.', 'IT', 75, 'Positive'
    UNION ALL SELECT 'New tax slab announced for consumer goods', 'Revised indirect taxes change the pricing outlook.', 'FMCG', 90, 'Neutral'
    UNION ALL SELECT 'Major retail expansion announced', 'A large energy conglomerate plans heavy retail spending.', 'Energy', 120, 'Positive'
    UNION ALL SELECT 'Global markets fall on recession fears', 'Broad selling as global growth estimates are cut.', 'ALL', 150, 'Negative'
    UNION ALL SELECT 'Electric vehicle policy boosts auto makers', 'Incentives extended for EV and battery plants.', 'Auto', 180, 'Positive'
    UNION ALL SELECT 'IT hiring slows amid global uncertainty', 'Clients defer discretionary technology spend.', 'IT', 210, 'Negative'
    UNION ALL SELECT 'Public sector bank posts record profit', 'Treasury gains lift banking profitability.', 'Banking', 250, 'Positive'
    UNION ALL SELECT 'Inflation cools to a two year low', 'Softer inflation raises hopes of rate cuts.', 'ALL', 300, 'Positive'
    UNION ALL SELECT 'FMCG margins pressured by input costs', 'Palm oil and packaging costs compress margins.', 'FMCG', 365, 'Negative'
) AS seed
WHERE NOT EXISTS (SELECT 1 FROM market_events);
