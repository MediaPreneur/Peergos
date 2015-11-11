package peergos.storage.dht;

import peergos.crypto.*;
import peergos.user.UserContext;
import peergos.util.*;
import peergos.util.ArrayOps;

import java.io.*;
import java.util.*;

public abstract class Message
{
    public static final boolean LOG = Args.hasArg("logMessages");
    public static final int KEY_BYTE_LENGTH = 32;

    public static enum Type
    {
        JOIN, ECHO, PUT, PUT_ACCEPT, GET, GET_RESULT
    }

    private static Type[] lookup = Type.values();
    private Type t;
    private List<NodeID> visited = new ArrayList(); // last element is previous node

    public Message(Type t)
    {
        this.t = t;
    }

    public Message(Type t, DataInput in) throws IOException
    {
        this.t = t;
        int n = in.readInt();
        for (int i = 0; i < n; i++)
        {
            visited.add(new NodeID(in));
        }
    }

    public String name()
    {
        return t.name();
    }

    public void write(DataOutput out) throws IOException
    {
        out.writeByte((byte) t.ordinal());
        out.writeInt(visited.size());
        for (NodeID hop : visited)
            hop.write(out);
    }

    public void addNode(NodeID n)
    {
        visited.add(n);
    }

    public List<NodeID> getHops()
    {
        return visited;
    }

    public long getOrigin()
    {
        return getHops().get(0).id;
    }

    public abstract long getTarget();

    public static Message read(DataInput in) throws IOException
    {
        int index = in.readByte() & 0xff;

        switch (lookup[index])
        {
            case JOIN:
                return new JOIN(in);
            case ECHO:
                return new ECHO(in);
            case PUT:
                return new PUT(in);
            case PUT_ACCEPT:
                return new PUT_ACCEPT(in);
            case GET:
                return new GET(in);
            case GET_RESULT:
                return new GET_RESULT(in);
        }
        throw new IllegalStateException("Unknown Message type: " + index);
    }

    public static class JOIN extends Message
    {
        public NodeID target;

        public JOIN(NodeID target)
        {
            super(Type.JOIN);
            this.target = target;
        }

        public JOIN(DataInput in) throws IOException
        {
            super(Type.JOIN, in);
            target = new NodeID(in);
        }

        public long getTarget()
        {
            return target.id;
        }

        public void write(DataOutput out) throws IOException
        {
            super.write(out);
            target.write(out);
        }
    }

    public static class ECHO extends Message
    {
        private NodeID target;
        private Set<NodeID> neighbours = new HashSet();

        public ECHO(NodeID target, Collection<Node> leftN, Collection<Node> rightN)
        {
            super(Type.ECHO);
            this.target = target;
            for (Node n: leftN)
                neighbours.add(n.node);
            for (Node n: rightN)
            neighbours.add(n.node);
        }

        public ECHO(DataInput in) throws IOException
        {
            super(Type.ECHO, in);
            target = new NodeID(in);
            int n = in.readInt();
            for (int i=0; i < n; i++)
                neighbours.add(new NodeID(in));
        }

        public long getTarget()
        {
            return target.id;
        }

        public Set<NodeID> getNeighbours()
        {
            return neighbours;
        }

        public void write(DataOutput out) throws IOException
        {
            super.write(out);
            target.write(out);
            out.writeInt(neighbours.size());
            for (NodeID n: neighbours)
                n.write(out);
        }
    }

    public static class PUT extends Message
    {
        private final long target;
        private final byte[] key;
        private final int len;
        private final byte[] owner;
        private final byte[] sharingKey;
        private final byte[] mapKey;
        private final byte[] proof;

        public PUT(byte[] key, int len, byte[] owner, byte[] sharingKey, byte[] mapKey, byte[] proof)
        {
            super(Type.PUT);
            this.key = key;
            target = ArrayOps.getLong(key, 0);
            this.len = len;
            this.owner = owner;
            this.sharingKey = sharingKey;
            this.mapKey = mapKey;
            this.proof = proof;
        }

        public PUT(DataInput in) throws IOException
        {
            super(Type.PUT, in);
            key = Serialize.deserializeByteArray(in, 64);
            len = in.readInt();
            target = ArrayOps.getLong(key, 0);
            owner = Serialize.deserializeByteArray(in, UserPublicKey.SIZE);
            sharingKey = Serialize.deserializeByteArray(in, 64);
            mapKey = Serialize.deserializeByteArray(in, 32);
            proof = Serialize.deserializeByteArray(in, 4096);
        }

        public long getTarget()
        {
            return target;
        }

        public void write(DataOutput out) throws IOException
        {
            super.write(out);
            Serialize.serialize(key, out);
            out.writeInt(len);
            Serialize.serialize(owner, out);
            Serialize.serialize(sharingKey, out);
            Serialize.serialize(mapKey, out);
            Serialize.serialize(proof, out);
        }

        public byte[] getOwner() {return owner;}

        public byte[] getSharingKey() {return sharingKey;}

        public byte[] getMapKey() {return mapKey;}

        public byte[] getProof() {return proof;}

        public byte[] getKey()
        {
            return key;
        }

        public int getSize()
        {
            return len;
        }
    }

    public static class PUT_ACCEPT extends Message
    {
        private final long target;
        private final byte[] key;
        private final int len;

        public PUT_ACCEPT(Message.PUT put)
        {
            super(Type.PUT_ACCEPT);
            this.key = put.getKey();
            target = put.getHops().get(0).id;
            this.len = put.getSize();
        }

        public PUT_ACCEPT(DataInput in) throws IOException
        {
            super(Type.PUT_ACCEPT, in);
            key = new byte[KEY_BYTE_LENGTH];
            in.readFully(key);
            len = in.readInt();
            target = in.readLong();
        }

        public long getTarget()
        {
            return target;
        }

        public void write(DataOutput out) throws IOException
        {
            super.write(out);
            out.write(key);
            out.writeInt(len);
            out.writeLong(target);
        }

        public byte[] getKey()
        {
            return key;
        }

        public int getSize()
        {
            return len;
        }
    }

    public static class GET extends Message
    {
        private final long target;
        private final byte[] key;

        public GET(byte[] key)
        {
            super(Type.GET);
            this.key = key;
            target = ArrayOps.getLong(key, 0);
        }

        public GET(byte[] key, long target)
        {
            super(Type.GET);
            this.key = key;
            this.target = target;
        }

        public GET(DataInput in) throws IOException
        {
            super(Type.GET, in);
            key = Serialize.deserializeByteArray(in, 64);
            target = ArrayOps.getLong(key, 0);
        }

        public long getTarget()
        {
            return target;
        }

        public void write(DataOutput out) throws IOException
        {
            super.write(out);
            Serialize.serialize(key, out);
        }

        public byte[] getKey()
        {
            return key;
        }
    }

    public static class GET_RESULT extends Message
    {
        private final long target;
        private final byte[] key;
        private final int len;

        public GET_RESULT(Message.GET put, int len)
        {
            super(Type.GET_RESULT);
            this.key = put.getKey();
            target = put.getOrigin();
            this.len = len;
        }

        public GET_RESULT(DataInput in) throws IOException
        {
            super(Type.GET_RESULT, in);
            key = new byte[KEY_BYTE_LENGTH];
            in.readFully(key);
            len = in.readInt();
            target = in.readLong();
        }

        public long getTarget()
        {
            return target;
        }

        public void write(DataOutput out) throws IOException
        {
            super.write(out);
            out.write(key);
            out.writeInt(len);
            out.writeLong(target);
        }

        public byte[] getKey()
        {
            return key;
        }

        public int getSize()
        {
            return len;
        }
    }
}