package com.stocksim.util;

import com.stocksim.model.User;

/** Singleton holding the logged-in user. Cleared completely on logout. */
public final class UserSession {
    private static final UserSession INSTANCE = new UserSession();
    private volatile User currentUser;

    private UserSession() {
    }

    public static UserSession getInstance() {
        return INSTANCE;
    }

    public void start(User user) {
        this.currentUser = user;
    }

    public User getCurrentUser() {
        return currentUser;
    }

    public boolean isLoggedIn() {
        return currentUser != null;
    }

    /** The logged-in user's id; fails loudly if nobody is logged in. */
    public int requireUserId() {
        User user = currentUser;
        if (user == null) {
            throw new IllegalStateException("No user is logged in.");
        }
        return user.userId();
    }

    public void logout() {
        currentUser = null;
    }
}
