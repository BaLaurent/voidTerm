package com.voidterm.app;

import com.termux.terminal.TerminalSession;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.times;

/**
 * Unit tests for {@link SessionManager}.
 * Uses mocked TerminalSessions since the real constructor spawns a process.
 */
public class SessionManagerTest {

    private SessionManager manager;
    private SessionManager.SessionChangeListener listener;

    @Before
    public void setUp() {
        manager = new SessionManager();
        listener = mock(SessionManager.SessionChangeListener.class);
        manager.setListener(listener);
    }

    private TerminalSession addMockSession(String name) {
        TerminalSession session = mock(TerminalSession.class);
        // Simulate what createSession does, but with a mock
        // We can't use createSession because TerminalSession constructor spawns a process
        // Instead, test the public API by accessing internals via the list
        return session;
    }

    // --- createSession is not testable (TerminalSession spawns process) ---
    // --- Test switchToSession, removeSession, renameSession via reflection or
    //     by using a testable subset of the API ---

    @Test
    public void initialState_noSessions() {
        assertEquals(0, manager.getSessionCount());
        assertEquals(-1, manager.getCurrentIndex());
        assertNull(manager.getCurrentSession());
    }

    @Test
    public void switchToSession_outOfBounds_isNoop() {
        manager.switchToSession(-1);
        manager.switchToSession(0);
        manager.switchToSession(5);
        // No exception, no listener calls
        verify(listener, never()).onSessionSwitched(any());
    }

    @Test
    public void finishAllSessions_emptyList_isNoop() {
        manager.finishAllSessions();
        assertEquals(0, manager.getSessionCount());
        assertEquals(-1, manager.getCurrentIndex());
    }

    @Test
    public void renameSession_outOfBounds_isNoop() {
        manager.renameSession(-1, "test");
        manager.renameSession(0, "test");
        verify(listener, never()).onSessionListChanged();
    }

    // --- Tests using reflection to add sessions without spawning processes ---

    private TerminalSession injectMockSession(String name) {
        TerminalSession session = mock(TerminalSession.class);
        session.mSessionName = name;
        try {
            java.lang.reflect.Field sessionsField = SessionManager.class.getDeclaredField("sessions");
            sessionsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.List<TerminalSession> sessions =
                    (java.util.List<TerminalSession>) sessionsField.get(manager);
            sessions.add(session);

            java.lang.reflect.Field indexField = SessionManager.class.getDeclaredField("currentIndex");
            indexField.setAccessible(true);
            indexField.setInt(manager, sessions.size() - 1);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return session;
    }

    @Test
    public void switchToSession_validIndex_switchesAndNotifies() {
        TerminalSession s1 = injectMockSession("Session 1");
        TerminalSession s2 = injectMockSession("Session 2");
        reset(listener);

        manager.switchToSession(0);

        assertEquals(0, manager.getCurrentIndex());
        assertSame(s1, manager.getCurrentSession());
        verify(listener).onSessionSwitched(s1);
        verify(listener).onSessionListChanged();
    }

    @Test
    public void switchToSession_sameIndex_isNoop() {
        injectMockSession("Session 1");
        injectMockSession("Session 2");
        // currentIndex is 1 (last added)
        reset(listener);

        manager.switchToSession(1);
        verify(listener, never()).onSessionSwitched(any());
    }

    @Test
    public void removeSession_middleSession_adjustsIndex() {
        TerminalSession s1 = injectMockSession("Session 1");
        TerminalSession s2 = injectMockSession("Session 2");
        TerminalSession s3 = injectMockSession("Session 3");
        // currentIndex = 2 (s3)
        reset(listener);

        TerminalSession result = manager.removeSession(s2);

        assertEquals(2, manager.getSessionCount());
        assertNotNull(result);
        // currentIndex was 2, removed index 1 (before current) → decremented to 1
        assertEquals(1, manager.getCurrentIndex());
        assertSame(s3, manager.getCurrentSession());
    }

    @Test
    public void removeSession_currentSession_switchesToNext() {
        TerminalSession s1 = injectMockSession("Session 1");
        TerminalSession s2 = injectMockSession("Session 2");
        TerminalSession s3 = injectMockSession("Session 3");
        // currentIndex = 2 (s3)
        reset(listener);

        TerminalSession result = manager.removeSession(s3);

        assertEquals(2, manager.getSessionCount());
        // Was at index 2, removed it, falls back to index 1 (s2)
        assertEquals(1, manager.getCurrentIndex());
        assertSame(s2, result);
    }

    @Test
    public void removeSession_lastSession_returnsNull() {
        TerminalSession s1 = injectMockSession("Session 1");
        reset(listener);

        TerminalSession result = manager.removeSession(s1);

        assertNull(result);
        assertEquals(0, manager.getSessionCount());
        assertEquals(-1, manager.getCurrentIndex());
        verify(listener).onSessionRemoved(s1, null);
    }

    @Test
    public void removeSession_unknownSession_returnsCurrentSession() {
        TerminalSession s1 = injectMockSession("Session 1");
        TerminalSession unknown = mock(TerminalSession.class);
        reset(listener);

        TerminalSession result = manager.removeSession(unknown);

        assertSame(s1, result);
        assertEquals(1, manager.getSessionCount());
    }

    @Test
    public void renameSession_validIndex_updatesNameAndNotifies() {
        TerminalSession s1 = injectMockSession("Session 1");
        reset(listener);

        manager.renameSession(0, "my-server");

        assertEquals("my-server", s1.mSessionName);
        verify(listener).onSessionListChanged();
    }

    @Test
    public void finishAllSessions_clearsEverything() {
        TerminalSession s1 = injectMockSession("Session 1");
        TerminalSession s2 = injectMockSession("Session 2");

        manager.finishAllSessions();

        assertEquals(0, manager.getSessionCount());
        assertEquals(-1, manager.getCurrentIndex());
        assertNull(manager.getCurrentSession());
        verify(s1).finishIfRunning();
        verify(s2).finishIfRunning();
    }

    @Test
    public void getSessions_returnsUnmodifiableList() {
        injectMockSession("Session 1");

        try {
            manager.getSessions().add(mock(TerminalSession.class));
            // Should throw UnsupportedOperationException
            throw new AssertionError("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // correct behavior
        }
    }
}
