package com.stocksim.controller;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;

import java.util.ArrayList;
import java.util.List;

/** Hosts the three experiment modules as tabs. */
public class LabController extends PageController {
    @FXML private TabPane tabs;
    private final List<PageController> modules = new ArrayList<>();

    @Override
    protected void onAttach() {
        for (String[] module : new String[][] {{"Market Scenarios", "scenarios"},
                {"What-If Simulator", "whatif"}, {"Strategy Backtesting", "backtest"}}) {
            FXMLLoader loader = Navigator.loader(module[1]);
            Tab tab = new Tab(module[0], Navigator.load(loader));
            tab.setClosable(false);
            PageController controller = loader.getController();
            controller.attach(shell);
            modules.add(controller);
            tabs.getTabs().add(tab);
        }
        tabs.getSelectionModel().selectedIndexProperty().addListener((o, a, b) -> refresh());
    }

    /** 0 scenarios, 1 what-if, 2 backtesting. */
    public PageController select(int index) {
        tabs.getSelectionModel().select(index);
        return modules.get(index);
    }

    @Override
    public void refresh() {
        modules.get(tabs.getSelectionModel().getSelectedIndex()).refresh();
    }
}
