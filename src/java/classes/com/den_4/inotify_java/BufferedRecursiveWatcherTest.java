/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package com.den_4.inotify_java;

import com.den_4.inotify_java.enums.Event;
import com.den_4.inotify_java.exceptions.InotifyException;

/**
 *
 * @author Philipp C. Heckel
 */
public class BufferedRecursiveWatcherTest {
    public static void main(String[] args) throws Exception {
	System.out.println("FIX non-existing moves and adds: mkdir ONE && mv ONE TWO");
	System.out.println("FIX non-existing moves and adds: mkdir ONE && rmdir ONE");

	BufferedRecursiveWatcher watcher = new BufferedRecursiveWatcher(true, 200, false);
	//BufferedRecursiveWatcher.DEBUG = true;

	InotifyEventListener listener = new InotifyEventListener() {
		@Override
		public void filesystemEventOccurred(InotifyEvent e) {
		    System.err.println(e);
		}

		@Override
		public void queueFull(EventQueueFull e) {
		    System.err.println(e);
		}
        };

	int wd = 0;
        try {
            wd = watcher.addRecursiveWatch("/home/pheckel/Coding/clonebox-platop-pictures",
		Event.Create, Event.Delete, Event.Close_Write, Event.Moved_From_To, Event.Moved_From, Event.Moved_To);

            watcher.addRecursiveListener(wd, listener);

	     wd = watcher.addRecursiveWatch("/home/pheckel/Coding/clonebox-platop-videos",
		Event.Create, Event.Delete, Event.Close_Write, Event.Moved_From_To, Event.Moved_From, Event.Moved_To);

            watcher.addRecursiveListener(wd, listener);

	} catch (InotifyException ex) {
            ex.printStackTrace();
	    return;
        }


	watcher.start();
    }
}
