package net.minecraftforge.common.util;

import java.util.AbstractSequentialList;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.ListIterator;
import java.util.Map;
import java.util.NoSuchElementException;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;

/**
 * A {@link java.util.List List&lt;TileEntity&gt;} that is optimized for removal of single {@link TileEntity TileEntities} as well as removal of all {@link TileEntity TileEntities} belonging to a specific chunk<br>
 * The complexity of operations is mostly shared with that of {@link java.util.LinkedList LinkedList}<br>
 * Index based operations are fully supported, but are heavily discouraged
 */
public class TEList extends AbstractSequentialList<TileEntity>
{
    private static class Node
    {
        Node prev = null;
        Node next = null;

        TileEntity value;

        Node cPrev = null;
        Node cNext = null;

        Node(final TileEntity value)
        {
            this.value = value;
        }

        void remove()
        {
            this.prev.next = this.next;
            this.next.prev = this.prev;

            this.cPrev.cNext = this.cNext;
            this.cNext.cPrev = this.cPrev;
        }

        void insertAfter(final Node after)
        {
            after.next.prev = this;
            this.next = after.next;
            after.next = this;
            this.prev = after;
        }

        void cInsertAfter(final Node after)
        {
            after.cNext.cPrev = this;
            this.cNext = after.cNext;
            after.cNext = this;
            this.cPrev = after;
        }
    }

    private static class ChunkList
    {
        final Node head = new Node(null);
        final Node tail = new Node(null);

        ChunkList()
        {
            this.head.cNext = this.tail;
            this.tail.cPrev = this.head;
        }
    }

    private final Node head = new Node(null);
    private final Node tail = new Node(null);

    private int size = 0;

    private final Map<ChunkPos, ChunkList> chunks = new HashMap<>();

    public TEList()
    {
        this.head.next = this.tail;
        this.tail.prev = this.head;
    }

    private class ListIt implements ListIterator<TileEntity>
    {
        private Node cur;
        private int curIndex;
        private Node lastRet = null;

        private int expectedModCount = TEList.this.modCount;

        ListIt(final boolean reverse)
        {
            this.cur = reverse ? TEList.this.tail.prev : TEList.this.head;
            this.curIndex = reverse ? TEList.this.size - 1 : -1;
        }

        private void checkForComodification()
        {
            if (this.expectedModCount != TEList.this.modCount)
                throw new ConcurrentModificationException();
        }

        @Override
        public boolean hasNext()
        {
            return this.curIndex < TEList.this.size - 1;
        }

        private Node nextNode()
        {
            ++this.curIndex;
            return this.cur = this.cur.next;
        }

        @Override
        public TileEntity next()
        {
            this.checkForComodification();

            if (this.curIndex == TEList.this.size - 1)
                throw new NoSuchElementException("Invalid index " + this.curIndex + " (size: " + TEList.this.size + ")");

            return (this.lastRet = this.nextNode()).value;
        }

        @Override
        public boolean hasPrevious()
        {
            return this.curIndex >= 0;
        }

        private Node prevNode()
        {
            --this.curIndex;
            return this.cur = this.cur.prev;
        }

        @Override
        public TileEntity previous()
        {
            this.checkForComodification();

            if (this.curIndex < 0)
                throw new NoSuchElementException("Invalid index " + this.curIndex);

            return (this.lastRet = this.prevNode().next).value;
        }

        @Override
        public int nextIndex()
        {
            return this.curIndex + 1;
        }

        @Override
        public int previousIndex()
        {
            return this.curIndex;
        }

        @Override
        public void remove()
        {
            this.checkForComodification();

            if (this.lastRet == null)
                throw new IllegalStateException();

            this.lastRet.remove();

            if (this.cur == this.lastRet)
                this.prevNode();

            this.lastRet = null;
            --TEList.this.size;

            ++TEList.this.modCount;
            ++this.expectedModCount;
        }

        @Override
        public void set(final TileEntity t)
        {
            this.checkForComodification();

            if (this.lastRet == null)
                throw new IllegalStateException();

            if (toChunkPos(t).equals(toChunkPos(this.lastRet.value)))
            {
                this.lastRet.value = t;
                return;
            }

            final boolean wentBack = this.cur != this.lastRet;

            final Node oldNode = this.lastRet;
            oldNode.value = t;

            this.remove();
            --TEList.this.modCount;
            --this.expectedModCount;

            this.add(oldNode);

            this.lastRet = this.cur;

            if (wentBack)
                this.prevNode();
        }

        @Override
        public void add(final TileEntity t)
        {
            this.checkForComodification();

            final Node toAdd = new Node(t);
            this.add(toAdd);

            ++TEList.this.modCount;
            ++this.expectedModCount;
        }

        private void add(final Node toAdd)
        {
            final ChunkPos chunkPos = toChunkPos(toAdd.value);

            final ChunkList chunkList = TEList.this.chunks.computeIfAbsent(chunkPos, (n) -> new ChunkList());

            Node node; //the node to pass to cInsertAfter

            if (chunkList.head.cNext == chunkList.tail)
                node = chunkList.head;
            else
            {
                node = this.cur;
                while (node != TEList.this.head && !toChunkPos(node.value).equals(chunkPos))
                    node = node.prev;

                if (node == TEList.this.head)
                    node = chunkList.head;
            }

            toAdd.insertAfter(this.cur);
            toAdd.cInsertAfter(node);

            this.nextNode();

            this.lastRet = null;
            ++TEList.this.size;
        }
    }

    @Override
    public ListIterator<TileEntity> listIterator(final int index)
    {
        if (index < 0)
            throw new IndexOutOfBoundsException("Invalid index " + index);

        if (index > this.size)
            throw new IndexOutOfBoundsException("Invalid index " + index + " (size: " + this.size + ")");

        final boolean reverse = index > this.size / 2;

        final ListIt ret = new ListIt(reverse);

        if (reverse)
        {
            for (int cur = this.size - 1; cur > index - 1; --cur)
                ret.previous();
        }
        else
        {
            for (int cur = -1; cur < index - 1; ++cur)
                ret.next();
        }

        ret.lastRet = null;

        return ret;
    }

    private static ChunkPos toChunkPos(final TileEntity t)
    {
        return new ChunkPos(t.getPos());
    }

    @Override
    public int size()
    {
        return this.size;
    }

    @Override
    public boolean add(final TileEntity t)
    {
        final Node node = new Node(t);

        final ChunkList chunkList = this.chunks.computeIfAbsent(toChunkPos(t), (v) -> new ChunkList());

        node.cInsertAfter(chunkList.tail.cPrev);
        node.insertAfter(this.tail.prev);
        ++this.size;

        ++this.modCount;
        return true;
    }

    @Override
    public void clear()
    {
        this.chunks.clear();
        this.head.next = this.tail;
        this.tail.prev = this.head;

        ++this.modCount;
    }

    @Override
    public boolean remove(final Object o)
    {
        if (o instanceof TileEntity)
        {
            final TileEntity tileEntity = (TileEntity) o;

            final ChunkList chunkList = this.chunks.get(toChunkPos(tileEntity));

            if (chunkList != null)
            {
                for (Node cur = chunkList.head.cNext; cur.cNext != null; cur = cur.cNext)
                {
                    if (tileEntity.equals(cur.value))
                    {
                        cur.remove();

                        --this.size;

                        ++this.modCount;
                        return true;
                    }
                }
            }
        }

        return false;
    }

    public void removeChunk(final Chunk chunk)
    {
        final ChunkList chunkList = this.chunks.remove(chunk.getPos());

        if (chunkList == null)
            return;

        for (Node cur = chunkList.head.cNext; cur.cNext != null; cur = cur.cNext)
        {
            cur.remove();
            --this.size;
        }

        ++this.modCount;
    }
}
