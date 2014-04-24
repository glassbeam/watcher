/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package com.den_4.inotify_java;

import com.den_4.inotify_java.enums.Event;
import com.den_4.inotify_java.enums.EventModifier;
import com.den_4.inotify_java.enums.WatchModifier;
import com.den_4.inotify_java.exceptions.InotifyException;
import com.den_4.inotify_java.exceptions.InvalidWatchDescriptorException;
import java.io.File;
import java.io.FileFilter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Recursively watches directories for events.
 *
 * @author Philipp Heckel
 */
public class RecursiveWatcher extends Inotify {
    /**
     * Set this to true to enable debugging.
     */
    public static boolean DEBUG = false;

    /**
     * The recursive watcher needs to listen to more events than the
     * user might possibly catch, e.g. create directories.
     *
     * <p>Returned by protected overridable method getWatcherEventMask().
     */
    protected static final int watcherEventMask =
	      Event.Moved_From.value() | Event.Moved_To.value()
	    | Event.Moved_From_To.value() | Event.Create.value() | Event.Delete.value();

    protected final Map<Integer, Set<InotifyEventListener>> listeners;

    /**
     * Internal listener
     */
    protected InotifyEventListener listener;
    
    /**
     * Maps a watch descriptor to an event array. Needed for adding new
     * sub-directories with the same event mask to the parent watch descriptor.
     */
    protected Map<Integer, Event[]> watchEventMap;

    /**
     * Maps a sub WD to the root parent WD.
     */
    protected Map<Integer, Integer> watchRootMap;

    /**
     * Maps root parent WD to its children.
     */
    protected Map<Integer, List<Integer>> rootWatchMap;

    /**
     * (parentWD, list of childWDs)
     */
    protected Map<Integer, List<Integer>> parentChildrenWatchMap;

    public RecursiveWatcher() throws InotifyException {
	super();

	this.watchEventMap = new TreeMap<Integer, Event[]>();
	this.watchRootMap = new TreeMap<Integer, Integer>();
	this.rootWatchMap = new TreeMap<Integer, List<Integer>>();
	this.parentChildrenWatchMap = new TreeMap<Integer, List<Integer>>();

	this.listeners = new TreeMap<Integer, Set<InotifyEventListener>>();
	this.listener = new InotifyEventListener() {
	    @Override
	    public void filesystemEventOccurred(InotifyEvent e) {
		listenerFilesystemEventOccurred(e); }

	    @Override
	    public void queueFull(EventQueueFull e) {
		listenerQueueFull(e); }
	};
    }

    public void addRecursiveListener(int watchDescriptor, InotifyEventListener listener) {
	synchronized (listeners) {
	    Set<InotifyEventListener> wdListeners = listeners.get(watchDescriptor);

	    if (wdListeners == null)
		wdListeners = new HashSet<InotifyEventListener>();

	    wdListeners.add(listener);
	    listeners.put(watchDescriptor, wdListeners);
	}
    }

    public void removeRecursiveListener(int watchDescriptor, InotifyEventListener listener) {
	synchronized (listeners) {
	    Set<InotifyEventListener> wdListeners = listeners.get(watchDescriptor);

	    if (wdListeners == null)
		return;

	    wdListeners.remove(listener);
	    listeners.put(watchDescriptor, wdListeners);
	}
    }

    /**
     * Add a watch to the given directory and its subfolders. 
     *
     * <p><strong>Note:</strong> the more directories are being watched, the higher is
     * the management effort for the recursive watcher. Also: inotify has an upper limit
     * for watches, so adding /home/yourname/ won't work in most cases.
     */
    public synchronized int addRecursiveWatch(String path, Event... events) throws InotifyException {
	if (DEBUG) System.err.println("["+new Date()+"] Adding watch: "+path);

	// The actually watched events must include CREATE, MOVED_FROM, MOVED_TO and DELETE
	Event[] watcherEvents = Event.maskToEvents(Event.eventsToMask(events) | watcherEventMask);

	int wdParent = addFolderToWatch(null, path, watcherEvents);

	watchEventMap.put(wdParent, events);
	rootWatchMap.put(wdParent, new LinkedList<Integer>());	

	// Add sub-folders, and sub-sub folders, ...	
	addSubFoldersToWatch(wdParent, path, watcherEvents);
	
	return wdParent;
    }

    protected synchronized void addSubFoldersToWatch(int wdRoot, String path, Event... watcherEvents) throws InotifyException {
	// Add sub-folders, and sub-sub folders, ...
	FileFilter onlyDirectories = new FileFilter() {
	    @Override public boolean accept(File file) { return file.isDirectory(); } };

	List<File> subFolders = new LinkedList<File>(Arrays.asList(new File(path).listFiles(onlyDirectories)));

	while (subFolders.size() > 0) {
	    File subFolder = subFolders.remove(0);
	    if (DEBUG) System.err.println("["+new Date()+"] - Adding sub-folder watch: "+subFolder.getAbsolutePath());

	    addFolderToWatch(wdRoot, subFolder.getAbsolutePath(), watcherEvents);

	    // Get the subfolders of the current subfolder
	    subFolders.addAll(Arrays.asList(subFolder.listFiles(onlyDirectories)));
	}
    }

    protected synchronized int addFolderToWatch(Integer wdRoot, String path, Event... watcherEvents) throws InotifyException {
	int wd = super.addWatch(path, watcherEvents);
	super.addListener(wd, listener);

	// Add references to root
	if (wdRoot != null) {
	    watchRootMap.put(wd, wdRoot);

	    List<Integer> childWDs = rootWatchMap.get(wdRoot);

	    if (childWDs == null)
		throw new InotifyException("Parent watch descriptor "+wdRoot+" not known.");

	    childWDs.add(wd);
	}

	// Add references to parent
	if (wdRoot != null) {
	    File parentFolder = new File(path).getParentFile();
	    Integer parentWD = pathWatchMap.get(parentFolder.getAbsolutePath());

	    if (parentWD == null)
		throw new InotifyException("Parent watch descriptor "+parentWD+" for path "+parentFolder+" not known.");

	    List<Integer> childWDs = parentChildrenWatchMap.get(parentWD);

	    if (childWDs == null) {
		childWDs = new ArrayList<Integer>();
		parentChildrenWatchMap.put(parentWD, childWDs);
	    }
		//throw new InotifyException("No children for parent watch descriptor "+parentWD+" (path "+parentFolder+") known. This should not happen!");

	    childWDs.add(wd);
	}

	// Add myself to the parent-child WD map
//	parentChildrenWatchMap.put(wd, new ArrayList<Integer>());

	return wd;
    }

    public synchronized void removeRecursiveWatch(String path) throws InvalidWatchDescriptorException {
	int wdRoot = super.getWatchDescriptor(path);
	
	// Remove selected item
	if (DEBUG) System.err.println("["+new Date()+"] Removing watch: "+path);
	
	//super.removeListener(wd, this);
	//super.pathWatchMap.remove(path);
	//super.watchPathMap.remove(wdRoot);

	removeSubFoldersFromWatch(wdRoot, path);
	//super.removeWatch(wdRoot);
	if (DEBUG) printMaps();
    }

    protected synchronized void removeSubFoldersFromWatch(int wdRoot, String path) throws InvalidWatchDescriptorException {
	List<Integer> childWDs = rootWatchMap.get(wdRoot);
	List<String> subFolders = new ArrayList<String>();

	if (childWDs == null)
	    throw new InvalidWatchDescriptorException("Unknown watch descriptor "+wdRoot);

	for (Integer wdSubFolder : childWDs) {
	    String subFolder = super.getPath(wdSubFolder);

	    if (subFolder.length() < path.length() || !path.equals(subFolder.substring(0, path.length())))
		continue;
	    
	    subFolders.add(subFolder);
	}

	for (String subFolder : subFolders) {
	    if (DEBUG) System.err.println("["+new Date()+"] - Removing watch: "+subFolder);
	    removeFolderFromWatch(wdRoot, subFolder);
	}
    }

    protected synchronized void removeFolderFromWatch(int wdRoot, String path) throws InvalidWatchDescriptorException {
	Integer wd = super.getWatchDescriptor(path);

	rootWatchMap.get(wdRoot).remove(wd);

	//if (rootWatchMap.get(wdRoot).size() == 0)
	//    rootWatchMap.remove(wdRoot);

	watchRootMap.remove(wd);

	// Remove from parent map (from child-list)
	String parentPath = new File(path).getParentFile().getAbsolutePath();
	Integer wdParent = pathWatchMap.get(parentPath);

	if (wdParent != null) {
	    List<Integer> parentsChildWDs = parentChildrenWatchMap.get(wdParent);

	    if (parentsChildWDs != null) {
		parentsChildWDs.remove(wd);
		
		if (parentsChildWDs.isEmpty())
		    parentChildrenWatchMap.remove(wdParent);
	    }
	}
	
	// Remove from parent map (as parent)
	parentChildrenWatchMap.remove(wd);


	//super.removeListener(wd, listener);
	//System.err.println("["+new Date()+"] wd = "+wd);
	try {
	    super.removeWatch(wd);
	} catch (InotifyException e) {
	    //System.err.println("["+new Date()+"] err "+e);
	}
	
	super.pathWatchMap.remove(path);
	super.watchPathMap.remove(wd);
    }

    public void printMaps() {
	for (Map.Entry<String, Integer> m : super.pathWatchMap.entrySet())
	    System.err.println("["+new Date()+"] pathWatchMap: "+m.getKey()+ " = "+m.getValue());

	for (Map.Entry<Integer, String> m : super.watchPathMap.entrySet())
	    System.err.println("["+new Date()+"] watchPathMap: "+m.getKey()+ " = "+m.getValue());

	for (Map.Entry<Integer, Integer> m : watchRootMap.entrySet())
	    System.err.println("["+new Date()+"] watchRootMap: "+m.getKey()+ " = "+m.getValue());

	for (Map.Entry<Integer, List<Integer>> m : rootWatchMap.entrySet())
	    System.err.println("["+new Date()+"] rootWatchMap: "+m.getKey()+ " = "+m.getValue());

	for (Map.Entry<Integer, List<Integer>> m : parentChildrenWatchMap.entrySet())
	    System.err.println("["+new Date()+"] parentChildrenWatchMap: "+m.getKey()+ " = "+m.getValue());

	System.err.println("["+new Date()+"] ---");
    }

    protected void listenerFilesystemEventOccurred(InotifyEvent e) {
	if (DEBUG) System.err.println("["+new Date()+"] RAW: "+e);
	
	// Figure out the root/parent folder
	Integer wdParent = e.getSource();
	Integer wdRoot = watchRootMap.get(wdParent);

	if (wdRoot == null) // If no parent exists, use source directly
	    wdRoot = wdParent;

	Event[] events = watchEventMap.get(wdRoot);
	int userEventMask = Event.eventsToMask(events);

	// Add / remove folders (--> recursive watch!)
	try {
	    if (wdRoot == null || events == null)
		throw new InotifyException("No responsible parent-watch found for path: "+e.getContextualName());

	    // If this is a 'create'-like event, add new sub-folder(s)
	    if (e.aboutDirectory() && (e.isCreate() || e.isMovedTo())) {
		if (DEBUG) System.err.println("["+new Date()+"] \n\nNew folder (or moved folder): "+e.getContextualName());

		addFolderToWatch(wdRoot, e.getContextualName(), events);
		addSubFoldersToWatch(wdRoot, e.getContextualName(), events);
		
		if (DEBUG) printMaps();
	    }

	    else if (e.aboutDirectory() && e instanceof MoveInotifyEvent) {
		MoveInotifyEvent me = (MoveInotifyEvent) e;

		String fromPath = me.getFromEvent().getContextualName();
		String fromParentPath = new File(fromPath).getParentFile().getAbsolutePath();
		String toPath = me.getToEvent().getContextualName();
		String toParentPath = new File(toPath).getParentFile().getAbsolutePath();

		if (DEBUG) System.err.println("["+new Date()+"] \n\n Moved folder from "+fromPath + " to "+toPath);

		// Change maps to new path (or add if non-existant)
		Integer wd = pathWatchMap.remove(fromPath);

		if (wd == null) {
		    wd = addFolderToWatch(wdRoot, toPath, events);
		    addSubFoldersToWatch(wdRoot, toPath, events);
		}

		pathWatchMap.put(toPath, wd);
		watchPathMap.put(wd, toPath);

		// Change parent mapping
		if (!fromParentPath.equals(toParentPath)) {
		    // Remove from old
		    List<Integer> oldParentsChildWDs = parentChildrenWatchMap.get(wdParent);
		    oldParentsChildWDs.remove(wd);

		    if (oldParentsChildWDs.isEmpty())
			parentChildrenWatchMap.remove(new Integer(wdParent));

		    // Add to new
		    int wdToParent = pathWatchMap.get(toParentPath);
		    List<Integer> newParentsChildWDs = parentChildrenWatchMap.get(wdToParent);

		    if (newParentsChildWDs == null) {
			newParentsChildWDs = new ArrayList<Integer>();
			parentChildrenWatchMap.put(wdToParent, newParentsChildWDs);
		    }

		    newParentsChildWDs.add(wd);
		}

		// Change paths to children
		List<Integer> allChildWDs = getChildWatchDescriptors(wd);

		for (Integer childWD : allChildWDs) {
		    String oldChildPath = watchPathMap.get(childWD);
		    String newChildPath = toPath + oldChildPath.substring(fromPath.length());

		    pathWatchMap.remove(oldChildPath);
		    pathWatchMap.put(newChildPath, childWD);
		    watchPathMap.put(childWD, newChildPath);
		}

		if (DEBUG) printMaps();
	    }

	    else if (e.aboutDirectory() && (e.isMovedFrom() || e.isDelete())) {
		if (DEBUG) System.err.println("["+new Date()+"] \n\nFolder deleted or moved: "+e.getContextualName());

		removeSubFoldersFromWatch(wdRoot, e.getContextualName());

		if (DEBUG) printMaps();
	    }
	}
	catch (InotifyException ex) {
	    // TODO --> error via logger
	    System.err.println("["+new Date()+"] ERROR: "+ex);
	    ex.printStackTrace(System.err);
	    
	    return;
	}

	// Notify listeners
	// user:    10001
	// watcher: 11101  --> event AND user == user
	// event:   11001
	if (DEBUG) System.err.println("["+new Date()+"] user event mask: "+userEventMask+ ", event: "+e.getMask());

	if ((e.getMask() & userEventMask) > 0) {
	    if (DEBUG) System.err.println("["+new Date()+"] Event matches given user mask. Notifying listeners.");
	    
	    Set<InotifyEventListener> wdListeners = listeners.get(wdRoot);

	    if (wdListeners != null) {
		for (InotifyEventListener l : wdListeners)
		    l.filesystemEventOccurred(e);
	    }
	}
    }

    protected void listenerQueueFull(EventQueueFull e) {
	// TODO
	System.err.println("ERROR: Queue full: "+e);

	Set<InotifyEventListener> wdListeners = listeners.get((Integer) e.getSource());

	if (wdListeners != null) {
	    for (InotifyEventListener l : wdListeners)
		l.queueFull(e);
	}
    }

    /**
     * Recursively retrieves all child watch descriptors of the given
     * descriptor.
     * 
     * @param wd
     * @return
     */
    public synchronized List<Integer> getChildWatchDescriptors(int wd) {
	List<Integer> childWDs = new ArrayList<Integer>();
	List<Integer> recursiveChildWDs = new ArrayList<Integer>();

	if (parentChildrenWatchMap.containsKey(wd))
	    childWDs.addAll(parentChildrenWatchMap.get(wd));

	for(Integer childWD : childWDs)
	    recursiveChildWDs.addAll(getChildWatchDescriptors(childWD));

	childWDs.addAll(recursiveChildWDs);
	
	return childWDs;
    }


}
