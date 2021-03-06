package ch.x42.terye.persistence;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.jcr.RepositoryException;

import ch.x42.terye.ItemImpl;
import ch.x42.terye.path.Path;
import ch.x42.terye.path.PathFactory;
import ch.x42.terye.persistence.id.ItemId;

public class ChangeLog {

    private Map<ItemId, ItemState> addedStates;
    private Map<ItemId, ItemState> modifiedStates;
    private Map<ItemId, ItemState> removedStates;
    // additionally keep track of moved states (the source of a move will be
    // present in removedStates, the destination in addedStates also)
    private Map<ItemId, ItemState> movedSrcStates;
    private Map<ItemId, ItemState> movedDestStates;

    public ChangeLog() {
        addedStates = new LinkedHashMap<ItemId, ItemState>();
        modifiedStates = new LinkedHashMap<ItemId, ItemState>();
        removedStates = new LinkedHashMap<ItemId, ItemState>();
        movedSrcStates = new LinkedHashMap<ItemId, ItemState>();
        movedDestStates = new LinkedHashMap<ItemId, ItemState>();
    }

    private ChangeLog(Map<ItemId, ItemState> addedStates,
            Map<ItemId, ItemState> modifiedStates,
            Map<ItemId, ItemState> removedStates,
            Map<ItemId, ItemState> movedSrcStates,
            Map<ItemId, ItemState> movedDestStates) {
        this.addedStates = addedStates;
        this.modifiedStates = modifiedStates;
        this.removedStates = removedStates;
        this.movedSrcStates = movedSrcStates;
        this.movedDestStates = movedDestStates;
    }

    /**
     * This method creates and returns a new change log containing all changes
     * that concern item states that are at path 'root' or below. The current
     * change log will keep all remaining changes.
     */
    public ChangeLog split(Path root) throws RepositoryException {
        Map<ItemId, ItemState> newAddedStates = new LinkedHashMap<ItemId, ItemState>();
        Map<ItemId, ItemState> newModifiedStates = new LinkedHashMap<ItemId, ItemState>();
        Map<ItemId, ItemState> newRemovedStates = new LinkedHashMap<ItemId, ItemState>();
        // move concerned added states to new change log
        Iterator<Entry<ItemId, ItemState>> iterator = addedStates.entrySet()
                .iterator();
        while (iterator.hasNext()) {
            Entry<ItemId, ItemState> entry = iterator.next();
            Path path = PathFactory.create(entry.getValue().getPath());
            if (path.isEquivalentTo(root) || path.isDescendantOf(root)) {
                newAddedStates.put(entry.getKey(), entry.getValue());
                iterator.remove();
            }
        }
        // move concerned modified states to new change log
        iterator = modifiedStates.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry<ItemId, ItemState> entry = iterator.next();
            Path path = PathFactory.create(entry.getValue().getPath());
            if (path.isEquivalentTo(root) || path.isDescendantOf(root)) {
                newModifiedStates.put(entry.getKey(), entry.getValue());
                iterator.remove();
            }
        }
        // move concerned removed states to new change log
        iterator = removedStates.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry<ItemId, ItemState> entry = iterator.next();
            Path path = PathFactory.create(entry.getValue().getPath());
            if (path.isEquivalentTo(root) || path.isDescendantOf(root)) {
                newRemovedStates.put(entry.getKey(), entry.getValue());
                iterator.remove();
            }
        }
        // XXX: not correct but ignore conflict cases
        Map<ItemId, ItemState> newMovedSrcStates = new LinkedHashMap<ItemId, ItemState>(
                movedSrcStates);
        Map<ItemId, ItemState> newMovedDestStates = new LinkedHashMap<ItemId, ItemState>(
                movedDestStates);

        return new ChangeLog(newAddedStates, newModifiedStates,
                newRemovedStates, newMovedSrcStates, newMovedDestStates);
    }

    public void added(ItemImpl item) {
        addedStates.put(item.getId(), item.getState());
    }

    public void modified(ItemImpl item) {
        if (!addedStates.containsKey(item.getId())) {
            modifiedStates.put(item.getId(), item.getState());
        }
    }

    public void removed(ItemImpl item) {
        if (addedStates.remove(item.getId()) == null) {
            modifiedStates.remove(item.getId());
            removedStates.put(item.getId(), item.getState());
        }
    }

    public void moved(ItemState src, ItemState dest) {
        movedSrcStates.put(src.getId(), src);
        movedDestStates.put(dest.getId(), dest);
    }

    public Collection<ItemState> getAddedStates() {
        return addedStates.values();
    }

    public Collection<ItemState> getModifiedStates() {
        return modifiedStates.values();
    }

    public Collection<ItemState> getRemovedStates() {
        return removedStates.values();
    }

    public Collection<ItemState> getMovedSrcStates() {
        return movedSrcStates.values();
    }

    public Collection<ItemState> getMovedDestStates() {
        return movedDestStates.values();
    }

    /**
     * This method returns the minimal subset of the removed states, such that
     * every removed state is either part of the returned set or a descendant of
     * one of the states in the returned set.
     */
    public List<ItemState> getMinimalRemovedStates() throws RepositoryException {
        // states contains all removed states
        List<ItemState> states = new LinkedList<ItemState>(
                removedStates.values());
        // sort states by increasing depth
        Collections.sort(states, new Comparator<ItemState>() {

            @Override
            public int compare(ItemState s1, ItemState s2) {
                Path p1 = PathFactory.create(s1.getPath());
                Path p2 = PathFactory.create(s2.getPath());
                if (p1.getDepth() < p2.getDepth()) {
                    return -1;
                } else if (p1.getDepth() == p2.getDepth()) {
                    return 0;
                }
                return 1;
            }

        });
        // start with an empty list of minimal roots
        List<ItemState> roots = new LinkedList<ItemState>();
        // loop through all states
        for (ItemState state : states) {
            boolean covered = false;
            // loop through all minimal roots
            for (ItemState root : roots) {
                // if this state is a descendant of one of the minimal roots, we
                // can discard it
                Path statePath = PathFactory.create(state.getPath());
                Path rootPath = PathFactory.create(root.getPath());
                if (statePath.isDescendantOf(rootPath)) {
                    covered = true;
                    break;
                }
            }
            // this state is not a descendant of one of the minimal roots, add
            // to minimal roots
            if (!covered) {
                roots.add(state);
            }
        }
        return roots;
    }

    public boolean isEmpty() {
        return (addedStates.isEmpty() && modifiedStates.isEmpty() && removedStates
                .isEmpty());
    }

    public void purge() {
        addedStates.clear();
        modifiedStates.clear();
        removedStates.clear();
        movedSrcStates.clear();
        movedDestStates.clear();
    }

}
