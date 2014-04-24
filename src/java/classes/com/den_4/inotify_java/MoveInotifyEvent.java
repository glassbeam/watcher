/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package com.den_4.inotify_java;

import com.den_4.inotify_java.enums.Event;

/**
 *
 * @author pheckel
 */
public class MoveInotifyEvent extends InotifyEvent {
    //public static final int MOVE_FROM_TO = 1 << Integer.SIZE-1;

    private InotifyEvent fromEvent;
    private InotifyEvent toEvent;

    public MoveInotifyEvent(InotifyEvent fromEvent, InotifyEvent toEvent) {
	super(
		fromEvent.getSource(),
		(fromEvent.getMask() | toEvent.getMask() | Event.Moved_From_To.value())
		    & ~Event.Moved_From.value() & ~Event.Moved_To.value(),
		fromEvent.getCookie()
	);

	this.fromEvent = fromEvent;
	this.toEvent = toEvent;
    }

    public InotifyEvent getFromEvent() {
	return fromEvent;
    }

    public void setFromEvent(InotifyEvent fromEvent) {
	this.fromEvent = fromEvent;
    }

    public InotifyEvent getToEvent() {
	return toEvent;
    }

    public void setToEvent(InotifyEvent toEvent) {
	this.toEvent = toEvent;
    }


    @Override
    public String toString() {
	StringBuilder sb = new StringBuilder();

	sb.append(getClass().getSimpleName());
	sb.append(" [FROM: ");
	sb.append(fromEvent);
	sb.append("], TO: ");
	sb.append(toEvent);
	sb.append("]");

	return sb.toString();
    }
}
