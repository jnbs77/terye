package ch.x42.terye.mk.hbase;

import static ch.x42.terye.mk.hbase.HBaseMicroKernelSchema.NODES;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.hadoop.hbase.TableNotFoundException;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.jackrabbit.mk.api.MicroKernel;
import org.apache.jackrabbit.mk.api.MicroKernelException;
import org.apache.jackrabbit.mongomk.impl.json.JsopParser;
import org.apache.jackrabbit.oak.commons.PathUtils;

import ch.x42.terye.mk.hbase.HBaseMicroKernelSchema.NodeTable;
import ch.x42.terye.mk.hbase.HBaseTableDefinition.Qualifier;

public class HBaseMicroKernel implements MicroKernel {

    private HBaseTableManager tableMgr;
    // XXX: temporarily use simple revision ids
    private static AtomicLong REVISION = new AtomicLong(0);
    // node cache
    private NodeCache cache;
    // cache for the revision ids we know are valid
    private Set<Long> validRevisions;
    // cache for the revision ids we know are not valid
    private Set<Long> invalidRevisions;

    public HBaseMicroKernel(HBaseAdmin admin) throws Exception {
        tableMgr = new HBaseTableManager(admin, HBaseMicroKernelSchema.TABLES);
        this.cache = new NodeCache(1000);
        this.validRevisions = new HashSet<Long>();
        this.invalidRevisions = new HashSet<Long>();
    }

    /**
     * Closes the HTable objects and HBaseAdmin and optionally drops the tables
     * used by the microkernel.
     */
    public void dispose(boolean dropTables) throws IOException {
        if (dropTables) {
            try {
                tableMgr.dropAllTables();
            } catch (TableNotFoundException e) {
                // nothing to do
            }
        }
        tableMgr.dispose();
    }

    @Override
    public String getHeadRevision() throws MicroKernelException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public String getRevisionHistory(long since, int maxEntries, String path)
            throws MicroKernelException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public String waitForCommit(String oldHeadRevisionId, long timeout)
            throws MicroKernelException, InterruptedException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public String getJournal(String fromRevisionId, String toRevisionId,
            String path) throws MicroKernelException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public String diff(String fromRevisionId, String toRevisionId, String path,
            int depth) throws MicroKernelException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public boolean nodeExists(String path, String revisionId)
            throws MicroKernelException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public long getChildNodeCount(String path, String revisionId)
            throws MicroKernelException {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public String getNodes(String path, String revisionId, int depth,
            long offset, int maxChildNodes, String filter)
            throws MicroKernelException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public String commit(String path, String jsonDiff, String revisionId,
            String message) throws MicroKernelException {
        try {
            // parse diff to an update object
            Update update = new Update();
            new JsopParser(path, jsonDiff, update.getJsopHandler()).parse();

            // generate new revision id
            long newRevId = generateNewRevisionId();

            // find the commit root
            String gcaPath = findGreatestCommonAncestor(update
                    .getModifiedNodes());
            int gcaDepth = PathUtils.getDepth(gcaPath);

            // read nodes (most of them will be in the cache)
            // XXX: replace with current head revision
            long headRevId = 1000L;
            Map<String, Node> nodesBefore = getNodes(update.getModifiedNodes(),
                    headRevId);

            // write changes to HBase
            Map<String, Put> puts = new HashMap<String, Put>();
            // - commit pointer
            for (String node : update.getModifiedNodes()) {
                Put put = getPut(node, newRevId, puts);
                // if this node is not the commit root...
                if (!node.equals(gcaPath)) {
                    // ...then add a commit pointer pointing to it
                    put.add(NodeTable.CF_DATA.toBytes(),
                            NodeTable.COL_COMMIT_POINTER.toBytes(), newRevId,
                            Bytes.toBytes(PathUtils.getDepth(node) - gcaDepth));
                }
            }
            // - added nodes
            for (String node : update.getAddedNodes()) {
                Put put = getPut(node, newRevId, puts);
                // child count
                put.add(NodeTable.CF_DATA.toBytes(),
                        NodeTable.COL_CHILD_COUNT.toBytes(), newRevId,
                        Bytes.toBytes(0L));
            }
            // - changed child counts
            for (Entry<String, Long> entry : update.getChangedChildCounts()
                    .entrySet()) {
                String node = entry.getKey();
                long childCount;
                if (nodesBefore.containsKey(node)) {
                    childCount = nodesBefore.get(node).getChildCount()
                            + entry.getValue();
                } else {
                    childCount = entry.getValue();
                }
                Put put = getPut(node, newRevId, puts);
                put.add(NodeTable.CF_DATA.toBytes(),
                        NodeTable.COL_CHILD_COUNT.toBytes(), newRevId,
                        Bytes.toBytes(childCount));
            }
            // - set properties
            for (Entry<String, Object> entry : update.getSetProperties()
                    .entrySet()) {
                String parentPath = PathUtils.getParentPath(entry.getKey());
                String name = PathUtils.getName(entry.getKey());
                Object value = entry.getValue();
                Put put = getPut(parentPath, newRevId, puts);
                Qualifier q = new Qualifier(NodeTable.DATA_PROPERTY_PREFIX,
                        name);
                put.add(NodeTable.CF_DATA.toBytes(), q.toBytes(), newRevId,
                        Bytes.toBytes(value.toString()));
            }
            // write batch
            // XXX: check results for null
            tableMgr.get(NODES).batch(new LinkedList<Put>(puts.values()));

            // check for potential concurrent modifications
            verifyUpdate(nodesBefore, update, newRevId);

            // write commit root
            Put put = getPut(gcaPath, newRevId, puts);
            put.add(NodeTable.CF_DATA.toBytes(),
                    NodeTable.COL_COMMIT.toBytes(), newRevId,
                    Bytes.toBytes(true));
            tableMgr.get(NODES).put(put);
        } catch (Exception e) {
            throw new MicroKernelException("Commit failed", e);
        }
        return null;
    }

    private Put getPut(String path, long revisionId, Map<String, Put> puts) {
        if (!puts.containsKey(path)) {
            Put put = new Put(NodeTable.pathToRowKey(path), revisionId);
            put.add(NodeTable.CF_DATA.toBytes(),
                    NodeTable.COL_LAST_REVISION.toBytes(), revisionId,
                    Bytes.toBytes(revisionId));
            puts.put(path, put);
        }
        return puts.get(path);
    }

    private void verifyUpdate(Map<String, Node> nodesBefore, Update update,
            long revisionId) throws IOException {
        Map<String, Result> nodesAfter = getRawNodes(update.getModifiedNodes(),
                revisionId);
        // loop through all nodes we have written
        for (String path : update.getModifiedNodes()) {
            boolean concurrentUpdate = false;
            Result after = nodesAfter.get(path);
            // get "last revision" column
            NavigableMap<Long, byte[]> lastRevCol = after.getMap()
                    .get(NodeTable.CF_DATA.toBytes())
                    .get(NodeTable.COL_LAST_REVISION.toBytes());
            // verify that our revision is contained in the column
            if (!lastRevCol.containsKey(revisionId)) {
                throw new MicroKernelException("Write of node " + path
                        + " failed");
            }
            // split column in two parts at our revision
            NavigableMap<Long, byte[]> lastRevColHead = lastRevCol.headMap(
                    revisionId, false);
            NavigableMap<Long, byte[]> lastRevColTail = lastRevCol.tailMap(
                    revisionId, false);
            // check that nobody wrote on top of our uncommitted changes
            if (!lastRevColHead.isEmpty()) {
                concurrentUpdate = true;
            } else {
                // make sure nobody wrote immediately before we did:
                // if the node didn't exist before...
                if (!nodesBefore.containsKey(path)) {
                    // ...then there should be no revision before ours
                    if (!lastRevColTail.isEmpty()) {
                        concurrentUpdate = true;
                    }
                } else {
                    // ...else the revision before ours must be equal to the one
                    // of the node read before our write
                    Node before = nodesBefore.get(path);
                    if (!lastRevColTail.firstKey().equals(
                            before.getLastRevision())) {
                        concurrentUpdate = true;
                    }
                }
            }
            if (concurrentUpdate) {
                // this update conflicted with some other update
                throw new MicroKernelException("Node " + path
                        + " was concurrently modified by another revision");
            }
        }
    }

    @Override
    public String branch(String trunkRevisionId) throws MicroKernelException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public String merge(String branchRevisionId, String message)
            throws MicroKernelException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public long getLength(String blobId) throws MicroKernelException {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public int read(String blobId, long pos, byte[] buff, int off, int length)
            throws MicroKernelException {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public String write(InputStream in) throws MicroKernelException {
        // TODO Auto-generated method stub
        return null;
    }

    /* private methods */

    /**
     * This method generates a new revision id. A revision id is a signed (but
     * non-negative) long composed of the concatenation (left-to-right) of
     * following fields:
     * 
     * <pre>
     *  --------------------------------------------------------------
     * |                    timestamp |    machine_id |       counter |
     *  --------------------------------------------------------------
     *  
     * - timestamp: signed int, 4 bytes, [0, Integer.MAX_VALUE]
     * - machine_id: unsigned short, 2 bytes, [0, 65535]
     * - counter: unsigned short, 2 bytes, [0, 65535]
     * </pre>
     * 
     * The unit of the timestamp is seconds and since we only have 4 bytes
     * available, we base it on an artificial epoch (defined in the method) in
     * order to have more values available. The machine id is generated using
     * the hashcode of the MAC address string and not guaranteed to be unique
     * (but very likely so). If the same machine commits a revision within the
     * same second, then the counter field is used in order to create a unique
     * revision id.
     * 
     * @return a new and unique revision id
     */
    private long generateNewRevisionId() {
        // XXX: temporarily use simple revision ids
        if (true) {
            return REVISION.addAndGet(1);
        }

        // NEVER EVER CHANGE THIS VALUE
        final long epoch = 1358845000;

        // timestamp
        long seconds = System.currentTimeMillis() / 1000 - epoch;
        long timestamp = seconds << 32;

        // machine id
        long machineId;
        try {
            NetworkInterface network = NetworkInterface
                    .getByInetAddress(InetAddress.getLocalHost());
            byte[] address = network.getHardwareAddress();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < address.length; i++) {
                sb.append(String.format("%02X", address[i]));
            }
            machineId = sb.hashCode();
        } catch (Throwable e) {
            machineId = new Random().nextInt();
        }
        machineId = (machineId << 16) & Long.decode("0x00000000FFFF0000");

        // counter
        // XXX: tbd
        long counter = 0;

        // assemble and return revision id
        return timestamp | machineId | counter;
    }

    private Map<String, Result> getRawNodes(Collection<String> paths,
            long revisionId) throws IOException {
        Map<String, Result> nodes = new LinkedHashMap<String, Result>();
        if (paths.isEmpty()) {
            return nodes;
        }
        List<Get> batch = new LinkedList<Get>();
        for (String path : paths) {
            Get get = new Get(NodeTable.pathToRowKey(path));
            get.setMaxVersions();
            get.setTimeRange(0L, revisionId + 1L);
            batch.add(get);
        }
        for (Result result : tableMgr.get(NODES).get(batch)) {
            if (!result.isEmpty()) {
                String path = NodeTable.rowKeyToPath(result.getRow());
                nodes.put(path, result);
            }
        }
        return nodes;
    }

    private Result getRawNode(String path, long revisionId) throws IOException {
        List<String> paths = new LinkedList<String>();
        paths.add(path);
        return getRawNodes(paths, revisionId).get(path);
    }

    private Map<String, Node> getNodes(Collection<String> paths, long revisionId)
            throws IOException {
        Map<String, Node> nodes = new TreeMap<String, Node>();
        List<String> pathsToRead = new LinkedList<String>();
        for (String path : paths) {
            Node node = cache.get(revisionId, path);
            if (node != null) {
                nodes.put(path, node);
            } else {
                pathsToRead.add(path);
            }
        }
        Map<String, Result> rawNodes = getRawNodes(pathsToRead, revisionId);
        nodes.putAll(parseNodes(rawNodes));
        return nodes;
    }

    /**
     * This method parses the specified raw nodes into nodes.
     * 
     * @param results the raw nodes
     * @return a map mapping paths to the corresponding node
     */
    private Map<String, Node> parseNodes(Map<String, Result> rawNodes)
            throws IOException {
        Map<String, Node> nodes = new LinkedHashMap<String, Node>();
        // loop through all raw nodes
        for (Result raw : rawNodes.values()) {
            String path = NodeTable.rowKeyToPath(raw.getRow());
            Node node = new Node(path);
            boolean nodeExists = false;
            NavigableMap<byte[], NavigableMap<Long, byte[]>> columns = raw
                    .getMap().get(NodeTable.CF_DATA.toBytes());
            // if the node contains the "commit" column...
            if (columns.containsKey(NodeTable.COL_COMMIT.toBytes())) {
                // ...then cache all of its revisions as valid ones
                validRevisions.addAll(columns.get(
                        NodeTable.COL_COMMIT.toBytes()).keySet());
            }
            // loop through all columns
            for (byte[] column : columns.keySet()) {
                // skip columns we're not interested in
                if (Arrays.equals(column, NodeTable.COL_COMMIT.toBytes())
                        || Arrays.equals(column,
                                NodeTable.COL_COMMIT_POINTER.toBytes())) {
                    continue;
                }
                // get the most recent column value with a valid revision
                byte[] value = null;
                NavigableMap<Long, byte[]> revisions = columns.get(column);
                // loop through all revisions, starting with the highest
                for (Long revision : revisions.keySet()) {
                    if (revisionIsValid(revision, raw)) {
                        // we have found a valid revision for that column
                        value = revisions.get(revision);
                        break;
                    }
                }
                // we haven't found a valid revision for that column...
                if (value == null) {
                    // ...thus it doesn't exist
                    continue;
                }
                nodeExists = true;
                if (Arrays
                        .equals(column, NodeTable.COL_LAST_REVISION.toBytes())) {
                    node.setLastRevision(Bytes.toLong(value));
                }
            }
            if (nodeExists) {
                nodes.put(path, node);
            }
        }
        return nodes;
    }

    /**
     * This method checks if a revision is valid. It first does a cache lookup
     * and if necessary then reads the specified commit root in order to verify
     * the validity of the revision.
     * 
     * @param revisionId the revision id to validate
     * @param result the raw node in which the revision occurs
     * @return true if the revision is valid, false otherwise
     */
    private boolean revisionIsValid(long revisionId, Result raw)
            throws IOException {
        // check cache of valid revisions
        if (validRevisions.contains(revisionId)) {
            return true;
        }
        // check cache of invalid revisions
        if (invalidRevisions.contains(revisionId)) {
            return false;
        }
        boolean valid = true;
        NavigableMap<Long, byte[]> commitCol = raw.getMap()
                .get(NodeTable.CF_DATA.toBytes())
                .get(NodeTable.COL_COMMIT.toBytes());
        if (commitCol == null || !commitCol.containsKey(revisionId)) {
            // read commit root
            NavigableMap<Long, byte[]> pointer = raw.getMap()
                    .get(NodeTable.CF_DATA.toBytes())
                    .get(NodeTable.COL_COMMIT_POINTER.toBytes());
            int ptr = Bytes.toInt(pointer.get(revisionId));
            String path = PathUtils.getAncestorPath(
                    NodeTable.rowKeyToPath(raw.getRow()), ptr);
            Result commitRoot = getRawNode(path, revisionId);
            if (commitRoot == null) {
                // commit root node doesn't exist
                valid = false;
            } else {
                commitCol = commitRoot.getMap()
                        .get(NodeTable.CF_DATA.toBytes())
                        .get(NodeTable.COL_COMMIT.toBytes());
                if (commitCol == null || !commitCol.containsKey(revisionId)) {
                    // commit root is not a commit root for this revision
                    valid = false;
                }
            }
        }
        // update cache
        if (valid) {
            validRevisions.add(revisionId);
        } else {
            invalidRevisions.add(revisionId);
        }
        return valid;
    }

    /**
     * Finds and returns the longest path that is an ancestor of all other paths
     * of the specified set.
     */
    private String findGreatestCommonAncestor(Collection<String> paths) {
        if (paths.isEmpty()) {
            return null;
        }
        // sort paths according to their depth
        ArrayList<String> sortedPaths = new ArrayList<String>(paths);
        Comparator<String> comparator = new Comparator<String>() {

            @Override
            public int compare(String path1, String path2) {
                Integer nb1 = PathUtils.getDepth(path1);
                Integer nb2 = PathUtils.getDepth(path2);
                return nb1.compareTo(nb2);
            }

        };
        Collections.sort(sortedPaths, comparator);
        // one path with least depth
        String path = sortedPaths.get(0);
        // try all subpaths until root is reached
        while (!PathUtils.denotesRoot(path)) {
            // verify path is an ancestor of all other paths
            boolean done = true;
            for (int i = 1; i < sortedPaths.size(); i++) {
                if (!PathUtils.isAncestor(path, sortedPaths.get(i))) {
                    // candidate is not an ancestor of this path
                    done = false;
                    break;
                }
            }
            if (done) {
                return path;
            }
            path = PathUtils.getParentPath(path);
        }
        // no ancestor found, return root
        return "/";
    }

}
