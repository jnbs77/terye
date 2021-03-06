package ch.x42.terye;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.jcr.ItemExistsException;
import javax.jcr.NodeIterator;
import javax.jcr.PathNotFoundException;
import javax.jcr.PropertyIterator;
import javax.jcr.RepositoryException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ch.x42.terye.observation.EventCollection;
import ch.x42.terye.observation.ObservationManagerImpl;
import ch.x42.terye.path.Path;
import ch.x42.terye.persistence.ChangeLog;
import ch.x42.terye.persistence.ItemState;
import ch.x42.terye.persistence.NodeState;
import ch.x42.terye.persistence.PersistenceManager;
import ch.x42.terye.persistence.PropertyState;
import ch.x42.terye.persistence.id.ItemId;
import ch.x42.terye.persistence.id.NodeId;
import ch.x42.terye.persistence.id.PropertyId;
import ch.x42.terye.value.ValueImpl;

public class ItemManager {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private SessionImpl session;
    private PersistenceManager persistenceManager;
    private ObservationManagerImpl observationManager;
    private ChangeLog log;
    private Map<String, ItemImpl> cache;
    // stores paths of items that have been removed in this session
    private Set<String> removed = new HashSet<String>();

    protected ItemManager(SessionImpl session,
            ObservationManagerImpl observationManager) {
        this.session = session;
        this.persistenceManager = ((WorkspaceImpl) session.getWorkspace())
                .getPersistenceManager();
        this.observationManager = observationManager;
        this.log = new ChangeLog();
        this.cache = new HashMap<String, ItemImpl>();
    }

    private void putToCache(ItemImpl item) {
        cache.put(item.getId().toString(), item);
    }

    /**
     * Cache lookup for an item corresponding to the specified id (i.e. item
     * type is considered).
     */
    private ItemImpl getFromCache(ItemId id) {
        ItemImpl item = cache.get(id.toString());
        if (item != null) {
            // check if found item type corresponds with id type
            if ((item.isNode() && id.denotesNode())
                    || (!item.isNode() && !id.denotesNode())) {
                return item;
            }
        }
        return null;
    }

    private void removeFromCache(ItemId id) {
        cache.remove(id.toString());
    }

    private void markRemoved(ItemId id) {
        removed.add(id.toString());
    }

    /**
     * Checks if the specified item or one of its ancestors has been removed in
     * this session.
     */
    private boolean isMarkedRemoved(ItemId id) {
        return removed.contains(id.toString());
    }

    private void unmarkRemoved(ItemId id) {
        removed.remove(id.toString());
    }

    private ItemImpl createNewInstance(ItemState state)
            throws RepositoryException {
        if (state.isNode()) {
            return new NodeImpl(session, (NodeState) state);
        } else {
            return new PropertyImpl(session, (PropertyState) state);
        }
    }

    public ItemImpl getItem(ItemId id) throws PathNotFoundException,
            RepositoryException {
        logger.debug("getItem({})", id);

        // check if item has been removed in this session
        if (isMarkedRemoved(id)) {
            throw new PathNotFoundException(id.toString());
        }

        // check if the item is cached
        ItemImpl item = getFromCache(id);
        if (item != null) {
            return item;
        }

        // load item state from persistent storage
        ItemState state = persistenceManager.loadItem(id);
        if (state == null) {
            throw new PathNotFoundException(id.toString());
        }

        // instantiate new in-memory copy and cache it
        item = createNewInstance(state);
        putToCache(item);

        return item;
    }

    public ItemImpl getItem(Path path) throws PathNotFoundException,
            RepositoryException {
        // we don't know what item type to expect, thus...
        try {
            // ...try getting a node at that path
            return getNode(path);
        } catch (PathNotFoundException e) {
            // ...there was no node so try getting a property
            return getProperty(path);
        }
    }

    public NodeImpl getNode(Path path) throws PathNotFoundException,
            RepositoryException {
        NodeId id = new NodeId(path.getCanonicalPath().toString());
        return (NodeImpl) getItem(id);
    }

    public PropertyImpl getProperty(Path path) throws RepositoryException {
        PropertyId id = new PropertyId(path.getCanonicalPath().toString());
        return (PropertyImpl) getItem(id);
    }

    public boolean itemExists(Path path) throws RepositoryException {
        try {
            getItem(path);
        } catch (PathNotFoundException e) {
            return false;
        }
        return true;
    }

    public boolean nodeExists(Path path) throws RepositoryException {
        try {
            getNode(path);
        } catch (PathNotFoundException e) {
            return false;
        }
        return true;
    }

    public boolean propertyExists(Path path) throws RepositoryException {
        try {
            getProperty(path);
        } catch (PathNotFoundException e) {
            return false;
        }
        return true;
    }

    public NodeImpl createNode(Path path, String primaryNodeTypeName)
            throws ItemExistsException, PathNotFoundException,
            RepositoryException {
        logger.debug("createNode({})", path);

        // check if path already exists
        if (itemExists(path)) {
            throw new ItemExistsException("An item already exists at: " + path);
        }

        // create new node
        NodeId id = new NodeId(path.getCanonicalPath().toString());
        NodeState state = new NodeState(id, primaryNodeTypeName);
        NodeImpl node = (NodeImpl) createNewInstance(state);
        putToCache(node);
        log.added(node);
        unmarkRemoved(id);

        // add to parent
        Path parentPath = path.getParent();
        NodeImpl parent = null;
        if (parentPath == null) {
            // only the case for the root node
            return node;
        }
        parent = getNode(parentPath);
        parent.addChild(node);
        log.modified(parent);

        return node;
    }

    public PropertyImpl createProperty(Path path, ValueImpl value)
            throws ItemExistsException, PathNotFoundException,
            RepositoryException {
        logger.debug("createProperty({})", path);

        // disallow nodes and properties having the same path
        if (nodeExists(path)) {
            throw new ItemExistsException("A node already exists at: " + path);
        }

        // create new property
        PropertyId id = new PropertyId(path.getCanonicalPath().toString());
        PropertyState state = new PropertyState(id, value);
        PropertyImpl property = new PropertyImpl(session, state, value);
        putToCache(property);
        log.added(property);
        unmarkRemoved(id);

        // add to parent
        NodeImpl parent = getNode(path.getParent());
        parent.addChild(property);
        log.modified(parent);

        return property;
    }

    public PropertyImpl updateProperty(Path path, ValueImpl value)
            throws RepositoryException {
        PropertyImpl property = getProperty(path);
        property.setValue(value);
        log.modified(property);
        return property;
    }

    public void removeItem(Path path) throws RepositoryException {
        removeItem(getItem(path));
    }

    public void removeItem(ItemImpl item) throws RepositoryException {
        logger.debug("removeItem({})", item.getId());

        // remove child nodes and properties recursively
        if (item.isNode()) {
            PropertyIterator pIterator = ((NodeImpl) item).getProperties();
            while (pIterator.hasNext()) {
                pIterator.nextProperty().remove();
            }
            NodeIterator nIterator = ((NodeImpl) item).getNodes();
            while (nIterator.hasNext()) {
                nIterator.nextNode().remove();
            }
        }

        // remove reference in parent
        NodeImpl parent = (NodeImpl) item.getParent();
        parent.removeChild(item);
        log.modified(parent);
        // remove item from cache
        removeFromCache(item.getId());
        log.removed(item);
        // add to paths removed in this session
        markRemoved(item.getId());
        // mark item removed
        item.markRemoved();
    }

    /**
     * Saves all changes that are at path 'root' or below.
     */
    public void persistChanges(Path root) throws RepositoryException {
        ChangeLog changes = log;
        if (!root.isRoot()) {
            changes = log.split(root);
        }
        persistenceManager.persist(changes);
        observationManager
                .dispatchEvents(new EventCollection(changes, session));
        log.purge();
    }

    /**
     * Refreshes all items that are at path 'root' or below.
     */
    public void refresh(Path root) throws RepositoryException {
        // list of items that have been removed by another session
        Set<ItemImpl> removed = new HashSet<ItemImpl>();
        // loop through all items of this session
        for (ItemImpl item : cache.values()) {
            Path path = item.getPathInternal();
            // check that the item is a descendant of the specified root and
            // that it hasn't been removed in this session
            if ((path.isEquivalentTo(root) || path.isDescendantOf(root))
                    && !isMarkedRemoved(item.getId())) {
                // load current persistent state
                ItemState state = persistenceManager.loadItem(item.getId());
                if (state == null) {
                    removed.add(item);
                } else {
                    // set loaded state
                    item.setState(state);
                }
            }
        }
        // mark removed items
        for (ItemImpl item : removed) {
            // remove item from cache
            removeFromCache(item.getId());
            // mark item removed
            item.markRemoved();
            // check if parent node has been loaded in this session
            NodeId parentId = new NodeId(item.getParentPath().toString());
            NodeImpl parent = (NodeImpl) getFromCache(parentId);
            if (parent != null) {
                // if the parent has not been refreshed during this call
                if (!(parent.getPathInternal().isEquivalentTo(root) || parent
                        .getPathInternal().isDescendantOf(root))) {
                    // remove the stale reference from its children
                    parent.removeChild(item);
                }
                // else the parent's state has been refreshed above and we don't
                // need to do anything
            }
        }
    }

    public boolean hasPendingChanges() {
        return !log.isEmpty();
    }

    public void move(ItemImpl item, Path destination)
            throws RepositoryException {
        doMove(item, destination, true);
    }

    private void doMove(ItemImpl item, Path destination, boolean isRoot)
            throws RepositoryException {
        // move child nodes and properties recursively
        if (item.isNode()) {
            PropertyIterator pIterator = ((NodeImpl) item).getProperties();
            while (pIterator.hasNext()) {
                PropertyImpl property = (PropertyImpl) pIterator.nextProperty();
                doMove(property, destination.resolve(property.getName()), false);
            }
            NodeIterator nIterator = ((NodeImpl) item).getNodes();
            while (nIterator.hasNext()) {
                NodeImpl node = (NodeImpl) nIterator.nextNode();
                doMove(node, destination.resolve(node.getName()), false);
            }
        }

        // if this is the root of the move, then remove it from parent's
        // children
        if (isRoot) {
            NodeImpl oldParent = (NodeImpl) item.getParent();
            oldParent.removeChild(item);
            log.modified(oldParent);
        }
        // remove the item
        removeFromCache(item.getId());
        log.removed(item);
        // add to paths removed in this session
        markRemoved(item.getId());

        // create new id
        ItemId id;
        if (item.isNode()) {
            id = new NodeId(destination.toString());
        } else {
            id = new PropertyId(destination.toString());
        }
        // clone item state
        ItemState old = item.getState();
        ItemState clone = old.clone(id);
        // set cloned state
        item.setState(clone);
        // add item
        putToCache(item);
        log.added(item);
        // if this is the root of the move, then add it to the new parent's
        // children
        if (isRoot) {
            NodeImpl newParent = (NodeImpl) item.getParent();
            newParent.addChild(item);
            log.modified(newParent);
        }

        // log item as moved
        log.moved(old, clone);
    }

}
