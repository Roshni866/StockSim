package com.stocksim.controller;

import javafx.fxml.FXML;

public class WelcomeController {
    @FXML private void openLogin() {
        Navigator.show("login");
    }

    @FXML private void openRegister() {
        Navigator.show("register");
    }
}
