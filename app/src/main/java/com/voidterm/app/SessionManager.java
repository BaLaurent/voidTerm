package com.voidterm.app;

import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manages multiple terminal sessions: creation, switching, removal.
 * Notifies a listener on state changes so the UI can update.
 */
public class SessionManager {

    public interface SessionChangeListener {
        void onSessionSwitched(TerminalSession session);
        void onSessionAdded(TerminalSession session);
        void onSessionRemoved(TerminalSession removed, TerminalSession switchedTo);
        void onSessionListChanged();
    }

    private final List<TerminalSession> sessions = new ArrayList<>();
    private int currentIndex = -1;
    private int sessionCounter = 0;
    private SessionChangeListener listener;

    public void setListener(SessionChangeListener listener) {
        this.listener = listener;
    }

    /**
     * Create a new terminal session, add it to the list, make it current.
     */
    public TerminalSession createSession(String shell, String cwd, String[] args,
                                         String[] env, TerminalSessionClient client) {
        sessionCounter++;
        TerminalSession session = new TerminalSession(shell, cwd, args, env, null, client);
        session.mSessionName = "Session " + sessionCounter;
        sessions.add(session);
        currentIndex = sessions.size() - 1;
        if (listener != null) {
            listener.onSessionAdded(session);
            listener.onSessionListChanged();
        }
        return session;
    }

    /**
     * Switch to a session by index. No-op if already current or out of bounds.
     */
    public void switchToSession(int index) {
        if (index < 0 || index >= sessions.size() || index == currentIndex) return;
        currentIndex = index;
        TerminalSession session = sessions.get(currentIndex);
        if (listener != null) {
            listener.onSessionSwitched(session);
            listener.onSessionListChanged();
        }
    }

    /**
     * Remove a session. Switches to adjacent session, or returns null if
     * the list is now empty (caller should create a new session).
     */
    public TerminalSession removeSession(TerminalSession session) {
        int index = sessions.indexOf(session);
        if (index < 0) return getCurrentSession();

        sessions.remove(index);
        session.finishIfRunning();

        if (sessions.isEmpty()) {
            currentIndex = -1;
            if (listener != null) {
                listener.onSessionRemoved(session, null);
                listener.onSessionListChanged();
            }
            return null;
        }

        // Adjust currentIndex: prefer same position, else last
        if (currentIndex >= sessions.size()) {
            currentIndex = sessions.size() - 1;
        } else if (index < currentIndex) {
            currentIndex--;
        } else if (index == currentIndex) {
            // Stay at same index (now points to next session) or move back
            if (currentIndex >= sessions.size()) {
                currentIndex = sessions.size() - 1;
            }
        }

        TerminalSession switchedTo = sessions.get(currentIndex);
        if (listener != null) {
            listener.onSessionRemoved(session, switchedTo);
            listener.onSessionSwitched(switchedTo);
            listener.onSessionListChanged();
        }
        return switchedTo;
    }

    public TerminalSession getCurrentSession() {
        if (currentIndex >= 0 && currentIndex < sessions.size()) {
            return sessions.get(currentIndex);
        }
        return null;
    }

    public List<TerminalSession> getSessions() {
        return Collections.unmodifiableList(sessions);
    }

    public int getSessionCount() {
        return sessions.size();
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    /**
     * Rename a session. Notifies listener to refresh the list.
     */
    public void renameSession(int index, String name) {
        if (index < 0 || index >= sessions.size()) return;
        sessions.get(index).mSessionName = name;
        if (listener != null) {
            listener.onSessionListChanged();
        }
    }

    /**
     * Finish all running sessions. Called from Activity.onDestroy().
     */
    public void finishAllSessions() {
        for (TerminalSession session : sessions) {
            session.finishIfRunning();
        }
        sessions.clear();
        currentIndex = -1;
    }
}
