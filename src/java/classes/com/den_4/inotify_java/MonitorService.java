/**
 * Copyright © 2009 Nick Bargnesi <nick@den-4.com>.  All rights reserved.
 *
 * inotify-java is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * inotify-java is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with inotify-java.  If not, see <http://www.gnu.org/licenses/>.
 *
 * File: MonitorService.java
 * Project: inotify-java
 * Package: com.den_4.inotify_java
 * Author: Nick Bargnesi
 */
package com.den_4.inotify_java;

import static java.lang.System.currentTimeMillis;

import java.lang.Thread.UncaughtExceptionHandler;
import java.text.ParseException;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;

import com.den_4.inotify_java.enums.Event;
import com.den_4.inotify_java.enums.EventModifier;
import com.den_4.inotify_java.enums.WatchModifier;
import com.den_4.inotify_java.exceptions.InotifyException;
import com.den_4.inotify_java.exceptions.InvalidWatchDescriptorException;

/**
 * The {@code MonitorService} class is an interface to the inotify API on Linux.
 * <p>
 * This service is suitable for running the lifetime of the virtual machine and
 * is guaranteed thread-safe.
 * </p>
 * <p>
 * This service supports {@code 32768} queued events. This value is double the
 * default specified by {@code /proc/sys/fs/inotify/max_queued_events} (as of
 * this writing). This yields a maximum of {@code 16384} events queued in the
 * Java stack. The remaining events will be queued in the native stack.
 * </p>
 * <p>
 * The service collects a number of statistics useful for tuning queue sizes as
 * needed.
 * <ul>
 * <li>{@link #getLargestQueueSize() largest queue size}
 * <li>{@link #getLastArrivalTime() last arrival time}
 * <li>{@link #getMinInterarrivalTime() minimum interarrival time}
 * <li>{@link #getMaxInterarrivalTime() maximum interarrival time}
 * <li>{@link #getMinServiceTime() minimum service time}
 * <li>{@link #getMaxServiceTime() maximum interarrival time}
 * </ul>
 * </p>
 * 
 * @author Nick Bargnesi
 * @since Version 2
 */
public final class MonitorService extends NativeInotify {

    /**
     * Default maximum number of queued events, from {@code
     * /proc/sys/fs/inotify/max_queued_events}.
     */
    public static final int DEFAULT_MAX_QUEUED_EVENTS = 16384;

    /**
     * Map of watch descriptors to paths.
     */
    Map<Integer, String> watchPathMap;

    /**
     * Map of paths to watch descriptors.
     */
    Map<String, Integer> pathWatchMap;

    /**
     * Map of watch descriptors to event listeners.
     */
    Map<Integer, Set<InotifyEventListener>> watchListenerMap;

    /**
     * The queue used for direct handoff by the servicing thread.
     */
    LinkedBlockingQueue<InotifyEvent> queue;

    /* Service statistics. */
    private int largestQueueSize;
    private long serviced;
    private double maxInterarrivalTime;
    private long lastArrivalTime;
    private double minInterarrivalTime;

    /** Maximum time spent servicing an inotify event. */
    double maxServiceTime;

    /** Minimum time spent servicing an inotify event. */
    double minServiceTime;

    /**
     * Queue capacity, defaulting to {@value #DEFAULT_MAX_QUEUED_EVENTS}.
     */
    private final int queueCapacity;

    /* Servicing threads. */
    private Thread consumer;
    private Thread producer;

    /**
     * Creates a monitor service with the supplied queue capacity.
     * 
     * @param maxQueued Maximum queue size, greater than zero
     * @throws InotifyException Thrown if the native inotify object could not be
     * constructed. The cause of the exception will be provided.
     */
    public MonitorService(final int maxQueued) throws InotifyException {
        super();
        if (maxQueued <= 0)
            throw new IllegalArgumentException("capacity <= 0");
        queueCapacity = maxQueued;

        sharedInit();
        threadInit();
        serviceInit();
    }

    /**
     * Creates a monitor service with a default queue capacity of
     * {@value #DEFAULT_MAX_QUEUED_EVENTS}.
     * 
     * @throws InotifyException Thrown if the native inotify object could not be
     * constructed. The cause of the exception will be provided.
     */
    public MonitorService() throws InotifyException {
        super();
        queueCapacity = DEFAULT_MAX_QUEUED_EVENTS;

        sharedInit();
        threadInit();
        serviceInit();
    }

    /**
     * Creates a monitor service with the supplied queue capacity and uncaught
     * exception handler.
     * 
     * @param maxQueued Maximum queue size, greater than zero
     * @param exceptionHandler Uncaught exception handler for servicing threads
     * @throws InotifyException Thrown if the native inotify object could not be
     * constructed. The cause of the exception will be provided.
     */
    public MonitorService(final int maxQueued,
            final UncaughtExceptionHandler exceptionHandler)
            throws InotifyException {
        super();
        if (maxQueued <= 0)
            throw new IllegalArgumentException("capacity <= 0");
        queueCapacity = maxQueued;

        sharedInit();
        threadInit();
        initHandler(exceptionHandler);
        serviceInit();
    }

    /**
     * Creates a monitor service with the supplied queue capacity. The thread
     * factory provided will be used to create two threads for servicing Inotify
     * events.
     * 
     * @param maxQueued Maximum queue size, greater than zero
     * @param factory Thread factory for producer/consumer servicing threads,
     * non-null
     * @throws InotifyException Thrown if the native inotify object could not be
     * constructed. The cause of the exception will be provided.
     */
    public MonitorService(final int maxQueued, final ThreadFactory factory)
            throws InotifyException {
        super();
        if (maxQueued <= 0)
            throw new IllegalArgumentException("capacity <= 0");
        if (factory == null)
            throw new IllegalArgumentException("null factory");
        queueCapacity = maxQueued;

        sharedInit();
        threadInit(factory);
        serviceInit();
    }

    /**
     * Creates a monitor service with the supplied queue capacity and uncaught
     * exception handler. The thread factory provided will be used create two
     * threads for servicing inotify events.
     * 
     * @param maxQueued Maximum queue size, greater than zero
     * @param factory Thread factory for producer/consumer servicing threads,
     * non-null
     * @param exceptionHandler Uncaught exception handler for servicing threads
     * @throws InotifyException Thrown if the native inotify object could not be
     * constructed. The cause of the exception will be provided.
     */
    public MonitorService(final int maxQueued, final ThreadFactory factory,
            final UncaughtExceptionHandler exceptionHandler)
            throws InotifyException {
        super();
        if (maxQueued <= 0)
            throw new IllegalArgumentException("capacity <= 0");
        if (factory == null)
            throw new IllegalArgumentException("null factory");
        queueCapacity = maxQueued;

        sharedInit();
        threadInit(factory);
        initHandler(exceptionHandler);
        serviceInit();
    }

    /**
     * Shared init routines.
     */
    private void sharedInit() {
        initQueue();
        mapInit();
    }

    /*
     * Creates the backing queue.
     */
    private void initQueue() {
        queue = new LinkedBlockingQueue<InotifyEvent>(queueCapacity);
    }

    /*
     * Create threads deferring to factory.
     */
    private void threadInit(final ThreadFactory f) {
        consumer = f.newThread(new QueueConsumer());
        producer = f.newThread(new QueueProducer());
    }

    /*
     * Create default threads.
     */
    private void threadInit() {
        consumer = new Thread(new QueueConsumer());
        consumer.setName("MonitorService(" + getFileDescriptor()
                + ") queue consumer");
        producer = new Thread(new QueueProducer());
        producer.setName("MonitorService(" + getFileDescriptor()
                + ") queue producer");
    }

    /*
     * Setup uncaught handlers.
     */
    private void initHandler(UncaughtExceptionHandler h) {
        consumer.setUncaughtExceptionHandler(h);
        producer.setUncaughtExceptionHandler(h);
    }

    /*
     * Establish maps.
     */
    private void mapInit() {
        watchPathMap = new ConcurrentHashMap<Integer, String>();
        pathWatchMap = new ConcurrentHashMap<String, Integer>();
        watchListenerMap = new ConcurrentHashMap<Integer, Set<InotifyEventListener>>();
    }

    /*
     * Initialize the service.
     */
    private void serviceInit() {
        consumer.start();
        producer.start();
    }

    /**
     * Returns the blocking queue backing the service.
     * 
     * @return Blocking queue containing inotify events
     */
    public BlockingQueue<InotifyEvent> getQueue() {
        return queue;
    }

    /**
     * Returns the service's queue capacity.
     * 
     * @return queue capacity
     */
    public int getQueueCapacity() {
        return queueCapacity;
    }

    /**
     * Returns the largest size of the service's backing queue.
     * 
     * @return Largest size of the queue
     */
    public int getLargestQueueSize() {
        return largestQueueSize;
    }

    /**
     * Returns the count of events delivered by the native queue.
     * 
     * @return Count of delivered events from the native queue
     */
    public long getServicedCount() {
        return serviced;
    }

    /**
     * Returns the maximum time, in milliseconds, between incoming events to the
     * service.
     * 
     * @return Maximum interarrival time of the backing queue
     */
    public double getMaxInterarrivalTime() {
        return maxInterarrivalTime;
    }

    /**
     * Returns the last arrival time as the the difference, measured in
     * milliseconds, between the current time and midnight, January 1, 1970 UTC.
     * 
     * @return Last arrival time to backing queue
     */
    public long getLastArrivalTime() {
        return lastArrivalTime;
    }

    /**
     * Returns the last arrival time as a {@link java.util.Date date}, which may
     * be null.
     * 
     * @param locale Locale to convert to
     * @return Date of last arrival time or null if no events have been received
     * @throws ParseException Thrown if errors occur processing the arrival time
     */
    public Date getLastArrivalDate(Locale locale) throws ParseException {
        if (locale == null)
            throw new NullPointerException("locale may not be null");
        if (lastArrivalTime == 0L) return null;
        Calendar ret = Calendar.getInstance(locale);
        ret.setTimeInMillis(lastArrivalTime);
        return ret.getTime();
    }

    /**
     * Returns the last arrival time as a {@link java.util.Date date} in the
     * {@code US} locale, which may be null.
     * 
     * @return Date of last arrival time or null if no events have been received
     * @throws ParseException Thrown if the errors occur processing the arrival
     * time
     */
    public Date getLastArrivalDate() throws ParseException {
        if (lastArrivalTime == 0L) return null;
        return getLastArrivalDate(Locale.US);
    }

    /**
     * Returns the minimum time, in milliseconds, between incoming events to the
     * service.
     * 
     * @return Minimum interarrival time of the backing queue
     */
    public double getMinInterarrivalTime() {
        return minInterarrivalTime;
    }

    /**
     * Returns the maximum time, in milliseconds, taken by a listener of inotify
     * events (known as the maximum service time).
     * 
     * @return Maximum service time associated to service
     */
    public double getMaxServiceTime() {
        return maxServiceTime;
    }

    /**
     * Returns the minimum time, in milliseconds, taken by a listener of inotify
     * events (known as the minimum service time).
     * 
     * @return Minimum service time associated to service
     */
    public double getMinServiceTime() {
        return minServiceTime;
    }

    /**
     * Receives an inotify event, notifies any necessary listeners, and performs
     * any necessary cleanup in the maps maintained by the class.
     * 
     * @param e InotifyEvent
     */
    @Override
    void eventHandler(InotifyEvent e) {

        long now = currentTimeMillis();
        if (lastArrivalTime == 0d) {
            lastArrivalTime = now;
        } else {
            long delta = now - lastArrivalTime;
            if (minInterarrivalTime == 0d || delta < minInterarrivalTime)
                minInterarrivalTime = delta;
            if (delta > maxInterarrivalTime) maxInterarrivalTime = delta;
            lastArrivalTime = now;
        }

        if (queue.offer(e)) {
            serviced++;
            int size = queue.size();
            if (size > largestQueueSize) largestQueueSize = size;
            return;
        }

        InotifyEvent ev = new InotifyEvent(e.getSource(),
                EventModifier.Event_Queue_Overflow.value());
        int wd = e.getSource();
        Set<InotifyEventListener> queue = watchListenerMap.get(wd);
        if (queue != null) {
            for (InotifyEventListener l : queue) {
                long t1 = currentTimeMillis();
                l.filesystemEventOccurred(ev);
                long t2 = currentTimeMillis();
                long delta = (t2 - t1);
                if (minServiceTime == 0d || delta < minServiceTime)
                    minServiceTime = delta;
                if (maxServiceTime == 0d || delta > maxServiceTime)
                    maxServiceTime = delta;
            }
        }
    }

    /**
     * Returns the path being watched for the provided watch descriptor.
     * 
     * @param watchDescriptor Watch descriptor identifying returning path
     * @return Path associated with provided watch descriptor, or null if the
     * watch descriptor is not valid
     */
    public String getPath(int watchDescriptor) {
        return watchPathMap.get(watchDescriptor);
    }

    /**
     * Returns the watch descriptor identifying the provided path.
     * 
     * @param path Path associated with returning watch descriptor
     * @return Watch descriptor identifying provided path, or {@code -1} if the
     * path is not being watched
     */
    public int getWatchDescriptor(String path) {
        if (path == null) return -1;
        Integer wd = pathWatchMap.get(path);
        if (wd == null) return -1;
        return wd;
    }

    /**
     * Adds the provided event listener as a receiver of {@link InotifyEvent
     * events} for the specified watch descriptor.
     * 
     * @param watchDescriptor Watch descriptor identifying path listener is
     * interested in
     * @param listener Listener to be notified of events occurred for the watch
     * descriptor provided
     * @throws IllegalArgumentException Thrown if the provided watch descriptor
     * is invalid
     */
    @ThreadSafe
    public void addListener(final int watchDescriptor,
            final InotifyEventListener listener) {
        if (listener == null)
            throw new NullPointerException("listener may not be null");
        if (!watchPathMap.containsKey(watchDescriptor))
            throw new IllegalArgumentException("invalid watch descriptor");

        Set<InotifyEventListener> val = watchListenerMap.get(watchDescriptor);
        if (val == null) {
            val = new CopyOnWriteArraySet<InotifyEventListener>();
            watchListenerMap.put(watchDescriptor, val);
        }
        val.add(listener);
    }

    /**
     * Removes the provided event listener from receiving {@link InotifyEvent
     * events} for the specified watch descriptor.
     * 
     * @param watchDescriptor Watch descriptor identifying path listener was
     * interested in
     * @param listener Listener to be removed
     * @return {@code true} if this set contained the listener
     * @throws NullPointerException Thrown if the provided listener is null
     * @throws IllegalArgumentException Thrown if the provided watch descriptor
     * is invalid
     */
    @ThreadSafe
    public boolean removeListener(final int watchDescriptor,
            final InotifyEventListener listener) {
        if (listener == null)
            throw new NullPointerException("listener may not be null");
        if (!watchListenerMap.containsKey(watchDescriptor))
            throw new IllegalArgumentException("invalid watch descriptor");
        Set<InotifyEventListener> val = watchListenerMap.get(watchDescriptor);
        if (val == null) return false;

        boolean ret = val.remove(listener);
        if (val.size() == 0) {
            watchListenerMap.remove(watchDescriptor);
        }
        return ret;
    }

    /**
     * Adds a watch for the specified path for the provided events.
     * 
     * @param path Path to be watched
     * @param events Events to watch for
     * @return Watch descriptor uniquely identifying this watched path
     * @throws InotifyException Thrown if the native inotify object could not be
     * constructed. The cause of the exception will be provided.
     */
    @ThreadSafe
    public int addWatch(final String path, final Event... events)
            throws InotifyException {
        if (path == null)
            throw new NullPointerException("path may not be null");

        int wm_mask = 0;
        int ev_mask = Event.eventsToMask(events);
        if (pathWatchMap.containsKey(path)) {
            wm_mask |= WatchModifier.Add.value();
        }

        return add_watch(path, wm_mask, ev_mask);
    }

    /**
     * Adds a watch for the specified path for the provided events and watch
     * modifiers.
     * 
     * @param path Path to be watched
     * @param watchModifiers Watch modifiers to augment addition of watch
     * @param events Events to watch for
     * @return Watch descriptor uniquely identifying this watched path
     * @throws InotifyException Thrown if the native inotify object could not be
     * constructed. The cause of the exception will be provided.
     */
    @ThreadSafe
    public int addWatch(final String path, final WatchModifier[] watchModifiers,
            final Event[] events) throws InotifyException {
        if (path == null)
            throw new NullPointerException("path may not be null");

        int wm_mask = WatchModifier.watchModifiersToMask(watchModifiers);
        int ev_mask = Event.eventsToMask(events);

        return add_watch(path, wm_mask, ev_mask);
    }

    /**
     * Adds a watch for the specified path using the provided watch modifier and
     * event masks.
     * 
     * @param path Path to be watched
     * @param watchModifiersMask Watch modifier mask to augment addition of
     * watch
     * @param eventMask Events to watch for
     * @return Watch descriptor uniqely identifying this watched path
     * @throws InotifyException Thrown if the native inotify object could not be
     * constructed. The cause of the exception will be provided.
     */
    @ThreadSafe
    public int addWatch(final String path, final int watchModifiersMask,
            final int eventMask) throws InotifyException {
        if (path == null)
            throw new NullPointerException("path may not be null");

        return add_watch(path, watchModifiersMask, eventMask);
    }

    /**
     * Private delegate method for adding watches.
     * 
     * @param path Path to watch
     * @param wm_mask Watch modifier mask
     * @param ev_mask Event mask
     * @return watch descriptor
     * @throws InotifyException Thrown if the native inotify object could not be
     * constructed. The cause of the exception will be provided.
     */
    private int add_watch(final String path, final int wm_mask,
            final int ev_mask) throws InotifyException {
        int wd = super.addWatch(path, wm_mask | ev_mask);
        pathWatchMap.put(path, wd);
        watchPathMap.put(wd, path);
        return wd;
    }

    /**
     * Removes the watch associated with the provided watch descriptor.
     * 
     * @param watchDescriptor Unique descriptor returned from
     * {@link #addWatch(String, Event...)}
     * @return {@code true} if a watch was removed, {@code false} otherwise
     * @throws InvalidWatchDescriptorException Thrown indicating an error in the
     * watch removal has occurred
     */
    @Override
    public boolean removeWatch(final int watchDescriptor)
            throws InvalidWatchDescriptorException {
        return remove_watch(watchDescriptor);
    }

    /**
     * Private delegate method for removing watches.
     * 
     * @param wd
     * @return {@code true} if a watch was removed, {@code false} otherwise
     * @throws InvalidWatchDescriptorException
     */
    private boolean remove_watch(final int wd)
            throws InvalidWatchDescriptorException {
        String path = watchPathMap.get(wd);
        watchPathMap.remove(wd);
        pathWatchMap.remove(path);
        watchListenerMap.remove(wd);
        return super.removeWatch(wd);
    }

    /**
     * Returns a String representation of this inotify object.
     * 
     * @return String representation of this inotify object
     */
    @Override
    public String toString() {
        StringBuilder s = new StringBuilder();
        s.append(getClass().getSimpleName());

        s.append(" [fd=");
        s.append(fileDescriptor);

        s.append(", watchPathMap=");
        s.append(watchPathMap);
        s.append(", pathWatchMap=");
        s.append(pathWatchMap);
        s.append(", watchListenerMap=");
        s.append(watchListenerMap);

        s.append("]");

        return s.toString();
    }

    /**
     * Reads inotify events from the native queue pushing them to the internal
     * service queue for the {@link QueueConsumer}.
     * 
     * @author Nick Bargnesi
     */
    final class QueueProducer implements Runnable {

        /**
         * Service the native queue.
         * 
         * @see java.lang.Thread#run()
         */
        @Override
        public void run() {
            read();
        }

    }

    /**
     * Reads inotify events from the internal service queue, notifying
     * listeners.
     * 
     * @author Nick Bargnesi
     */
    final class QueueConsumer implements Runnable {

        /**
         * Service the monitor service's queue.
         * 
         * @see java.lang.Thread#run()
         */
        @Override
        public void run() {
            while (isActive()) {
                try {
                    InotifyEvent e = queue.take();
                    String path = null;
                    if (e.getName() != null) {
                        path = watchPathMap.get(e.getSource());
                        if (path != null) {
                            if (path.charAt(path.length() - 1) == '/')
                                e.setContextualName(path.concat(e.getName()));
                            else
                                e.setContextualName(path.concat("/").concat(e.getName()));
                        }
                    }

                    int wd = e.getSource();
                    Set<InotifyEventListener> queue = watchListenerMap.get(wd);
                    if (queue != null) {
                        for (InotifyEventListener l : queue) {
                            long t1 = currentTimeMillis();
                            l.filesystemEventOccurred(e);
                            long t2 = currentTimeMillis();
                            long delta = (t2 - t1);
                            if (minServiceTime == 0d || delta < minServiceTime)
                                minServiceTime = delta;
                            if (maxServiceTime == 0d || delta > maxServiceTime)
                                maxServiceTime = delta;
                        }
                    }

                    if (e.isIgnored()) {
                        if (path == null) path = watchPathMap.get(wd);
                        watchPathMap.remove(wd);
                        pathWatchMap.remove(path);
                        watchListenerMap.remove(wd);
                    }
                } catch (InterruptedException e) {
                    // Keep going.
                }
            }
        }
    }
}
