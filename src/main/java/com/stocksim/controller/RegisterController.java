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

public class RegisterController {
    @FXML private TextField nameField;
    @FXML private TextField usernameField;
    @FXML private TextField emailField;
    @FXML private PasswordField passwordField;
    @FXML private PasswordField confirmField;
    @FXML private Label errorLabel;
    @FXML private Button registerButton;
    private final AuthService auth = new AuthService();

    @FXML private void register() {
        String name = nameField.getText();
        String username = usernameField.getText();
        String email = emailField.getText();
        String password = passwordField.getText();
        String confirmation = confirmField.getText();
        errorLabel.setText("");
        registerButton.setDisable(true);
        Async.<User>run(() -> auth.register(name, username, email, password, confirmation), user -> {
            UserSession.getInstance().start(user);
            CapitalController capital = Navigator.show("capital");
            capital.show(user);
        }, e -> {
            registerButton.setDisable(false);
            errorLabel.setText(Dialogs.message(e));
        });
    }

    @FXML private void openLogin() {
        Navigator.show("login");
    }

    @FXML private void back() {
        Navigator.show("welcome");
    }
}
