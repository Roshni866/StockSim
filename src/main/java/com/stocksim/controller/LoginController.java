package com.stocksim.controller;

import com.stocksim.model.User;
import com.stocksim.service.AuthService;
import com.stocksim.ui.Async;
import com.stocksim.ui.Dialogs;
import com.stocksim.util.UserSession;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

public class LoginController {
    @FXML private TextField identifierField;
    @FXML private PasswordField passwordField;
    @FXML private Label errorLabel;
    @FXML private Button loginButton;
    private final AuthService auth = new AuthService();

    @FXML private void login() {
        String identifier = identifierField.getText();
        String password = passwordField.getText();
        errorLabel.setText("Signing in…");
        loginButton.setDisable(true);
        Async.<User>run(() -> auth.login(identifier, password), user -> {
            passwordField.clear();
            UserSession.getInstance().start(user);
            Navigator.openShell();
        }, e -> {
            loginButton.setDisable(false);
            passwordField.clear();
            errorLabel.setText(Dialogs.message(e));
        });
    }

    @FXML private void openRegister() {
        Navigator.show("register");
    }

    @FXML private void back() {
        Navigator.show("welcome");
    }
}
