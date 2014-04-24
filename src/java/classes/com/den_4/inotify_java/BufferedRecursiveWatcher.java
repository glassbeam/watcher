/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package com.den_4.inotify_java;

import com.den_4.inotify_java.enums.Event;
import com.den_4.inotify_java.exceptions.InotifyException;
import java.util.Date;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Timer;
import java.util.TimerTask;

/**
 *
 * @author pheckel
 */
public class BufferedRecursiveWatcher extends RecursiveWatcher {
    public static final boolean DEFAULT_KILL_SOURCE_EVENTS = true;
    public static final int DEFAULT_DELAY = 200;
    public static final boolean DEFAULT_AUTOSTART = true;

    protected final LinkedList<TimedInotifyEvent> eventQueue;
    protected Timer timer;
    protected int delay;
    protected boolean killSourceEvents;

    public BufferedRecursiveWatcher() throws InotifyException {
	this(DEFAULT_KILL_SOURCE_EVENTS, DEFAULT_DELAY, DEFAULT_AUTOSTART);
    }

    public BufferedRecursiveWatcher(boolean killSourceEvents) throws InotifyException {
	this(killSourceEvents, DEFAULT_DELAY, DEFAULT_AUTOSTART);
    }

    public BufferedRecursiveWatcher(boolean killSourceEvents, int delay) throws InotifyException {
	this(killSourceEvents, delay, DEFAULT_AUTOSTART);

    }

    public BufferedRecursiveWatcher(boolean killSourceEvents, int delay, boolean autostart) throws InotifyException {
	super();

	this.delay = delay;
	this.killSourceEvents = killSourceEvents;
	this.eventQueue = new LinkedList<TimedInotifyEvent>();

	this.listener = new InotifyEventListener() {
	    @Override
	    public void filesystemEventOccurred(InotifyEvent e) {
		eventQueue.add(new TimedInotifyEvent(e, System.currentTimeMillis()));
		fireDelayedEvents();
	    }

	    @Override
	    public void queueFull(EventQueueFull e) {
		listenerQueueFull(e); }
	};

	if (autostart)
	    start();
    }

    public synchronized void start() {
	timer = new Timer();
	timer.scheduleAtFixedRate(new TimerTask() {
	    @Override public void run() { fireDelayedEvents(); } }, 0, delay);
    }

    public synchronized void stop() {
	if (timer == null)
	    return;

	timer.cancel();
	timer = null;
    }

    protected synchronized void fireDelayedEvents() {
	long now = System.currentTimeMillis();
	synchronized(eventQueue) {
	    while (eventQueue.size() > 0) {
		TimedInotifyEvent e = eventQueue.get(0);

		if (e.getTimestamp()+delay >= now)
		    break;

		// Fire event as usual
		//System.err.println("["+new Date()+"] processing "+e);
		eventQueue.remove(0);

		// If is 'moved from', look for a matching 'moved to' event (via cookie)
		// Note: this assumes that the FROM event always comes before the TO event
		if (e.getEvent().isMovedFrom()) {
		    TimedInotifyEvent movedFromEvent = e;
		    TimedInotifyEvent movedToEvent = null;

		    for (TimedInotifyEvent e2 : eventQueue) {
			if (movedFromEvent.getEvent().getCookie() != e2.getEvent().getCookie())
			    continue;

			movedToEvent = e2;
			break;
		    }

		    // 'moved to'-event found
		    if (movedToEvent != null) {
			eventQueue.remove(movedToEvent);

			// Fire them all!

			if (!killSourceEvents) {
			    listenerFilesystemEventOccurred(movedFromEvent.getEvent());
			    listenerFilesystemEventOccurred(movedToEvent.getEvent());
			}

			listenerFilesystemEventOccurred(new MoveInotifyEvent(e.getEvent(), movedToEvent.getEvent()));

			continue;
		    }
		}

		// Default behavior: fire event!
		listenerFilesystemEventOccurred(e.getEvent());
	    }
	}
    }

    protected class TimedInotifyEvent {
	private InotifyEvent event;
	private Long timestamp;

	public TimedInotifyEvent(InotifyEvent event, Long timestamp) {
	    this.event = event;
	    this.timestamp = timestamp;
	}

	public InotifyEvent getEvent() {
	    return event;
	}

	public Long getTimestamp() {
	    return timestamp;
	}

	@Override
	public boolean equals(Object obj) {
	    return ((TimedInotifyEvent) obj).getEvent().equals(getEvent())
		&& ((TimedInotifyEvent) obj).getTimestamp() == getTimestamp();
	}
    }

}
