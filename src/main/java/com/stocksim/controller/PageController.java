package com.stocksim.controller;

import com.stocksim.util.UserSession;

/**
 * Base class of every workspace controller. Pages are created once per login and
 * refreshed each time they are shown, so typed input survives navigation.
 */
public abstract class PageController {
    protected ShellController shell;

    void attach(ShellController owner) {
        this.shell = owner;
        onAttach();
    }

    /** One-time setup such as building table columns. */
    protected void onAttach() {
    }

    /** Reloads everything shown from the services. */
    public abstract void refresh();

    protected int userId() {
        return UserSession.getInstance().requireUserId();
    }
}
