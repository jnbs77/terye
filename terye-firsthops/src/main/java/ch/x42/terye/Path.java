package ch.x42.terye;

import java.util.Arrays;

public class Path {

    public static final String DELIMITER = "/";

    private String[] segments;
    private boolean isAbsolute = false;

    public Path(String path) {
        // TODO: check if argument is valid path string
        
        // check if path is absolute
        if (path.startsWith(Path.DELIMITER)) {
            isAbsolute = true;
            path = path.substring(1, path.length());
        }

        // remove trailing delimiter, if any
        if (path.endsWith(Path.DELIMITER)) {
            path = path.substring(0, path.length() - 1);
        }

        if (path.isEmpty()) {
            // split on empty string doesn't yield empty array
            segments = new String[0];
        } else {
            // split path into segments
            segments = path.split(Path.DELIMITER);
        }
    }

    private Path(String[] segments) {
        isAbsolute = true;
        this.segments = segments;
    }

    public boolean isAbsolute() {
        return isAbsolute;
    }

    public boolean isRelative() {
        return !isAbsolute();
    }

    public boolean isRoot() {
        return isAbsolute() && segments.length == 0;
    }

    public Path concat(Path that) {
        // make sure that lhs is absolute and rhs is relative
        // -> yields an absolute path
        if (isRelative()) {
            throw new IllegalArgumentException("Concat cannot be called on a relative path");
        } else if (that.isAbsolute()) {
            throw new IllegalArgumentException("Specified path must be relative");
        }

        // concat segments and return new path
        String[] segments = new String[this.segments.length + that.segments.length];
        System.arraycopy(this.segments, 0, segments, 0, this.segments.length);
        System.arraycopy(that.segments, 0, segments, this.segments.length, that.segments.length);
        return new Path(segments);
    }
    
    public Path concat(String path) {
        return concat(new Path(path));
    }
    
    public Path getCanonical() {
        // TODO: implement
        return this;
    }
    
    public Path getParent() {
        // should only be called on absolute paths
        if (!isAbsolute()) {
            throw new UnsupportedOperationException();
        }

        // return null for empty paths (root node or empty relative path)
        if (segments.length == 0) {
            return null;
        }

        return new Path(Arrays.copyOfRange(segments, 0, segments.length - 1));
    }

    public String getLastSegment() {
        return segments.length > 0 ? segments[segments.length - 1] : "";
    }

    @Override
    public String toString() {
        String str = isAbsolute ? "/" : "";
        for (int i = 0; i < segments.length; i++) {
            str += segments[i];
            if (i < segments.length - 1) {
                str += "/";
            }
        }
        return str;
    }
}