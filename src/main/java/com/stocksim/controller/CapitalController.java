package com.stocksim.controller;

import com.stocksim.model.User;
import com.stocksim.util.Formats;
import javafx.fxml.FXML;
import javafx.scene.control.Label;

/** Shown once after registration to introduce the virtual capital. */
public class CapitalController {
    @FXML private Label greetingLabel;
    @FXML private Label capitalLabel;

    void show(User user) {
        greetingLabel.setText("Welcome, " + user.name());
        capitalLabel.setText(Formats.money(user.balance()));
    }

    @FXML private void enter() {
        Navigator.openShell();
    }
}
