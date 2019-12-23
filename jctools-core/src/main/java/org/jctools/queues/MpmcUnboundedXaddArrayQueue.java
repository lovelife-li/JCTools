/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jctools.queues;

import org.jctools.queues.IndexedQueueSizeUtil.IndexedQueue;
import org.jctools.util.PortableJvmInfo;
import org.jctools.util.Pow2;
import org.jctools.util.UnsafeAccess;

import java.util.AbstractQueue;
import java.util.Iterator;

import static org.jctools.util.UnsafeAccess.UNSAFE;
import static org.jctools.util.UnsafeAccess.fieldOffset;

abstract class MpmcUnboundedXaddArrayQueuePad1<E> extends AbstractQueue<E> implements IndexedQueue
{
    long p01, p02, p03, p04, p05, p06, p07;
    long p10, p11, p12, p13, p14, p15, p16, p17;
}

// $gen:ordered-fields
abstract class MpmcUnboundedXaddArrayQueueProducerFields<E> extends MpmcUnboundedXaddArrayQueuePad1<E>
{
    private final static long P_INDEX_OFFSET =
        fieldOffset(MpmcUnboundedXaddArrayQueueProducerFields.class, "producerIndex");
    private volatile long producerIndex;

    @Override
    public final long lvProducerIndex()
    {
        return producerIndex;
    }

    final long getAndIncrementProducerIndex()
    {
        return UNSAFE.getAndAddLong(this, P_INDEX_OFFSET, 1);
    }

    final long getAndAddProducerIndex(long delta)
    {
        return UNSAFE.getAndAddLong(this, P_INDEX_OFFSET, delta);
    }
}

abstract class MpmcUnboundedXaddArrayQueuePad2<E> extends MpmcUnboundedXaddArrayQueueProducerFields<E>
{
    long p01, p02, p03, p04, p05, p06, p07, p08;
    long p10, p11, p12, p13, p14, p15, p16;
}

// $gen:ordered-fields
abstract class MpmcUnboundedXaddArrayQueueProducerBuffer<E> extends MpmcUnboundedXaddArrayQueuePad2<E>
{
    private static final long P_BUFFER_OFFSET =
        fieldOffset(MpmcUnboundedXaddArrayQueueProducerBuffer.class, "producerBuffer");
    private static final long P_CHUNK_INDEX_OFFSET =
        fieldOffset(MpmcUnboundedXaddArrayQueueProducerBuffer.class, "producerChunkIndex");

    private volatile MpmcUnboundedXaddChunk<E> producerBuffer;
    private volatile long producerChunkIndex;


    final long lvProducerChunkIndex()
    {
        return producerChunkIndex;
    }

    final boolean casProducerChunkIndex(long expected, long value)
    {
        return UNSAFE.compareAndSwapLong(this, P_CHUNK_INDEX_OFFSET, expected, value);
    }

    final void soProducerChunkIndex(long value)
    {
        UNSAFE.putOrderedLong(this, P_CHUNK_INDEX_OFFSET, value);
    }

    final MpmcUnboundedXaddChunk<E> lvProducerBuffer()
    {
        return this.producerBuffer;
    }

    final void soProducerBuffer(MpmcUnboundedXaddChunk<E> buffer)
    {
        UNSAFE.putOrderedObject(this, P_BUFFER_OFFSET, buffer);
    }
}

abstract class MpmcUnboundedXaddArrayQueuePad3<E> extends MpmcUnboundedXaddArrayQueueProducerBuffer<E>
{
    long p0, p1, p2, p3, p4, p5, p6, p7;
    long p10, p11, p12, p13, p14, p15, p16;
}

// $gen:ordered-fields
abstract class MpmcUnboundedXaddArrayQueueConsumerFields<E> extends MpmcUnboundedXaddArrayQueuePad3<E>
{
    private final static long C_INDEX_OFFSET =
        fieldOffset(MpmcUnboundedXaddArrayQueueConsumerFields.class, "consumerIndex");
    private final static long C_BUFFER_OFFSET =
        fieldOffset(MpmcUnboundedXaddArrayQueueConsumerFields.class, "consumerBuffer");

    private volatile long consumerIndex;
    private volatile MpmcUnboundedXaddChunk<E> consumerBuffer;

    @Override
    public final long lvConsumerIndex()
    {
        return consumerIndex;
    }

    final boolean casConsumerIndex(long expect, long newValue)
    {
        return UNSAFE.compareAndSwapLong(this, C_INDEX_OFFSET, expect, newValue);
    }

    final MpmcUnboundedXaddChunk<E> lvConsumerBuffer()
    {
        return this.consumerBuffer;
    }

    final void soConsumerBuffer(MpmcUnboundedXaddChunk<E> newValue)
    {
        UNSAFE.putOrderedObject(this, C_BUFFER_OFFSET, newValue);
    }
}

abstract class MpmcUnboundedXaddArrayQueuePad5<E> extends MpmcUnboundedXaddArrayQueueConsumerFields<E>
{
    long p0, p1, p2, p3, p4, p5, p6, p7;
    long p10, p11, p12, p13, p14, p15, p16;
}

/**
 * An MPMC array queue which starts at <i>initialCapacity</i> and grows unbounded in linked chunks.<br>
 * Differently from {@link MpmcArrayQueue} it is designed to provide a better scaling when more
 * producers are concurrently offering.
 *
 * @param <E>
 * @author https://github.com/franz1981
 */
public class MpmcUnboundedXaddArrayQueue<E> extends MpmcUnboundedXaddArrayQueuePad5<E>
    implements MessagePassingQueue<E>, QueueProgressIndicators
{
    private static final long ROTATION = -2;
    private final int chunkMask;
    private final int chunkShift;
    private final SpscArrayQueue<MpmcUnboundedXaddChunk<E>> freeBuffer;

    public MpmcUnboundedXaddArrayQueue(int chunkSize, int maxPooledChunks)
    {
        if (!UnsafeAccess.SUPPORTS_GET_AND_ADD_LONG)
        {
            throw new IllegalStateException("Unsafe::getAndAddLong support (JDK 8+) is required for this queue to work");
        }
        chunkSize = Pow2.roundToPowerOfTwo(chunkSize);
        final MpmcUnboundedXaddChunk<E> first = new MpmcUnboundedXaddChunk(0, null, chunkSize, true);
        soProducerBuffer(first);
        soProducerChunkIndex(0);
        soConsumerBuffer(first);
        chunkMask = chunkSize - 1;
        chunkShift = Integer.numberOfTrailingZeros(chunkSize);
        freeBuffer = new SpscArrayQueue<MpmcUnboundedXaddChunk<E>>(maxPooledChunks + 1);
        for (int i = 0; i < maxPooledChunks; i++)
        {
            freeBuffer.offer(new MpmcUnboundedXaddChunk(MpmcUnboundedXaddChunk.NIL_CHUNK_INDEX, null, chunkSize, true));
        }
    }

    public MpmcUnboundedXaddArrayQueue(int chunkSize)
    {
        this(chunkSize, 1);
    }

    private MpmcUnboundedXaddChunk<E> producerBufferOf(MpmcUnboundedXaddChunk<E> producerBuffer, long expectedChunkIndex)
    {
        long jumpBackward;
        while (true)
        {
            if (producerBuffer == null)
            {
                producerBuffer = lvProducerBuffer();
            }
            final long producerChunkIndex = producerBuffer.lvIndex();
            if (producerChunkIndex == MpmcUnboundedXaddChunk.NIL_CHUNK_INDEX)
            {
                //force an attempt to fetch it another time
                producerBuffer = null;
                continue;
            }
            jumpBackward = producerChunkIndex - expectedChunkIndex;
            if (jumpBackward >= 0)
            {
                break;
            }
            //try validate against the last producer chunk index
            if (lvProducerChunkIndex() == producerChunkIndex)
            {
                producerBuffer = appendNextChunks(producerBuffer, producerChunkIndex, chunkMask + 1, -jumpBackward);
            }
            else
            {
                producerBuffer = null;
            }
        }
        for (long i = 0; i < jumpBackward; i++)
        {
            //prev cannot be null, because is being released by index
            producerBuffer = producerBuffer.lpPrev();
            assert producerBuffer != null;
        }
        assert producerBuffer.lvIndex() == expectedChunkIndex;
        return producerBuffer;
    }

    private MpmcUnboundedXaddChunk<E> appendNextChunks(MpmcUnboundedXaddChunk<E> producerBuffer, long chunkIndex, int chunkSize, long chunks)
    {
        assert chunkIndex != MpmcUnboundedXaddChunk.NIL_CHUNK_INDEX;
        //prevent other concurrent attempts on appendNextChunk
        if (!casProducerChunkIndex(chunkIndex, ROTATION))
        {
            return null;
        }
        MpmcUnboundedXaddChunk<E> newChunk = null;
        for (long i = 1; i <= chunks; i++)
        {
            final long nextChunkIndex = chunkIndex + i;
            newChunk = freeBuffer.poll();
            if (newChunk != null)
            {
                //single-writer: producerBuffer::index == nextChunkIndex is protecting it
                assert newChunk.lvIndex() < producerBuffer.lvIndex();
                newChunk.spPrev(producerBuffer);
                //index set is releasing prev, allowing other pending offers to continue
                newChunk.soIndex(nextChunkIndex);
            }
            else
            {
                newChunk = new MpmcUnboundedXaddChunk<E>(nextChunkIndex, producerBuffer, chunkSize, false);
            }
            soProducerBuffer(newChunk);
            //link the next chunk only when finished
            producerBuffer.soNext(newChunk);
            producerBuffer = newChunk;
        }
        soProducerChunkIndex(chunkIndex + chunks);
        return newChunk;
    }

    @Override
    public long currentProducerIndex()
    {
        return lvProducerIndex();
    }

    @Override
    public long currentConsumerIndex()
    {
        return lvConsumerIndex();
    }

    @Override
    public boolean offer(E e)
    {
        if (null == e)
        {
            throw new NullPointerException();
        }
        final int chunkMask = this.chunkMask;
        final int chunkShift = this.chunkShift;
        final long producerSeq = getAndIncrementProducerIndex();
        final int pOffset = (int) (producerSeq & chunkMask);
        final long chunkIndex = producerSeq >> chunkShift;
        MpmcUnboundedXaddChunk<E> producerBuffer = lvProducerBuffer();
        if (producerBuffer.lvIndex() != chunkIndex)
        {
            producerBuffer = producerBufferOf(producerBuffer, chunkIndex);
        }
        final boolean isPooled = producerBuffer.isPooled();
        if (isPooled)
        {
            //wait any previous consumer to finish its job
            while (producerBuffer.lvElement(pOffset) != null)
            {

            }
        }
        producerBuffer.soElement(pOffset, e);
        if (isPooled)
        {
            producerBuffer.soSequence(pOffset, chunkIndex);
        }
        return true;
    }

    private static <E> E spinForElement(MpmcUnboundedXaddChunk<E> chunk, int offset)
    {
        E e;
        while ((e = chunk.lvElement(offset)) == null)
        {

        }
        return e;
    }

    private E rotateConsumerBuffer(MpmcUnboundedXaddChunk<E> consumerBuffer, MpmcUnboundedXaddChunk<E> next, int consumerOffset, long expectedChunkIndex)
    {
        while (next == null)
        {
            next = consumerBuffer.lvNext();
        }
        //we can freely spin awaiting producer, because we are the only one in charge to
        //rotate the consumer buffer and use next
        final E e = spinForElement(next, consumerOffset);
        final boolean pooled = next.isPooled();
        if (pooled)
        {
            while (next.lvSequence(consumerOffset) != expectedChunkIndex)
            {

            }
        }
        next.soElement(consumerOffset, null);
        next.spPrev(null);
        //save from nepotism
        consumerBuffer.soNext(null);
        if (consumerBuffer.isPooled())
        {
            final boolean offered = freeBuffer.offer(consumerBuffer);
            assert offered;
        }
        //expose next to the other consumers
        soConsumerBuffer(next);
        return e;
    }

    @Override
    public E poll()
    {
        final int chunkMask = this.chunkMask;
        final int chunkShift = this.chunkShift;
        long consumerIndex;
        MpmcUnboundedXaddChunk<E> consumerBuffer;
        int consumerOffset;
        final int chunkSize = chunkMask + 1;
        boolean firstElementOfNewChunk;
        E e = null;
        MpmcUnboundedXaddChunk<E> next = null;
        long pIndex = -1; // start with bogus value, hope we don't need it
        long chunkIndex;
        do
        {
            consumerIndex = this.lvConsumerIndex();
            consumerBuffer = this.lvConsumerBuffer();
            consumerOffset = (int) (consumerIndex & chunkMask);
            chunkIndex = consumerIndex >> chunkShift;
            firstElementOfNewChunk = consumerOffset == 0 && consumerIndex >= chunkSize;
            if (firstElementOfNewChunk)
            {
                //we don't care about < or >, because if:
                //- consumerBuffer::index < expectedChunkIndex: another consumer has rotated consumerBuffer,
                //  but not reused (yet, if possible)
                //- consumerBuffer::index > expectedChunkIndex: another consumer has rotated consumerBuffer,
                // that has been pooled and reused again
                //in both cases we have a stale view of the world with a not reliable next value.
                final long expectedChunkIndex = chunkIndex - 1;
                if (expectedChunkIndex != consumerBuffer.lvIndex())
                {
                    continue;
                }
                next = consumerBuffer.lvNext();
                //next could have been modified by another consumer, but:
                //- if null: it still needs to check q empty + casConsumerIndex
                //- if !null: it will fail on casConsumerIndex
                if (next == null)
                {
                    if (consumerIndex >= pIndex && // test against cached pIndex
                        consumerIndex == (pIndex = lvProducerIndex()))
                    { // update pIndex if we must
                        // strict empty check, this ensures [Queue.poll() == null iff isEmpty()]
                        return null;
                    }
                    //not empty: can attempt the cas
                }
            }
            else
            {
                final boolean pooled = consumerBuffer.isPooled();
                if (pooled) {
                    final long sequence = consumerBuffer.lvSequence(consumerOffset);
                    if (sequence != chunkIndex) {
                        if (sequence > chunkIndex) {
                            //stale view of the world
                            continue;
                        }
                        final long index = consumerBuffer.lvIndex();
                        if (index > chunkIndex)
                        {
                            //stale view of the world
                            continue;
                        }
                        //it cover both cases:
                        //- right chunk, awaiting element to be set
                        //- old chunk, awaiting rotation
                        //it allows to fail fast if the q is empty after the first element on the new chunk.
                        if (consumerIndex >= pIndex && // test against cached pIndex
                            consumerIndex == (pIndex = lvProducerIndex()))
                        { // update pIndex if we must
                            // strict empty check, this ensures [Queue.poll() == null iff isEmpty()]
                            return null;
                        }
                        continue;
                    }
                } else {
                    final long index = consumerBuffer.lvIndex();
                    if (index != chunkIndex || (e = consumerBuffer.lvElement(consumerOffset)) == null)
                    {
                        if (index > chunkIndex)
                        {
                            //stale view of the world
                            continue;
                        }
                        //it cover both cases:
                        //- right chunk, awaiting element to be set
                        //- old chunk, awaiting rotation
                        //it allows to fail fast if the q is empty after the first element on the new chunk.
                        if (consumerIndex >= pIndex && // test against cached pIndex
                            consumerIndex == (pIndex = lvProducerIndex()))
                        { // update pIndex if we must
                            // strict empty check, this ensures [Queue.poll() == null iff isEmpty()]
                            return null;
                        }
                        continue;
                    }
                }
            }
            if (casConsumerIndex(consumerIndex, consumerIndex + 1))
            {
                break;
            }
        }
        while (true);
        //if we are the firstElementOfNewChunk we need to rotate the consumer buffer
        if (firstElementOfNewChunk)
        {
            e = rotateConsumerBuffer(consumerBuffer, next, consumerOffset, chunkIndex);
        }
        else
        {
            if (consumerBuffer.isPooled()) {
                e = consumerBuffer.lvElement(consumerOffset);
                assert e != null;
            }
            assert !consumerBuffer.isPooled() ||
                (consumerBuffer.isPooled() && consumerBuffer.lvSequence(consumerOffset) == chunkIndex);
            consumerBuffer.soElement(consumerOffset, null);
        }
        return e;
    }

    @Override
    public E peek()
    {
        final int chunkMask = this.chunkMask;
        final int chunkShift = this.chunkShift;
        final int chunkSize = chunkMask + 1;
        long consumerIndex;
        E e;
        do
        {
            e = null;
            consumerIndex = this.lvConsumerIndex();
            MpmcUnboundedXaddChunk<E> consumerBuffer = this.lvConsumerBuffer();
            final int consumerOffset = (int) (consumerIndex & chunkMask);
            final long chunkIndex = consumerIndex >> chunkShift;
            final boolean firstElementOfNewChunk = consumerOffset == 0 && consumerIndex >= chunkSize;
            if (firstElementOfNewChunk)
            {
                final long expectedChunkIndex = chunkIndex - 1;
                if (expectedChunkIndex != consumerBuffer.lvIndex())
                {
                    continue;
                }
                final MpmcUnboundedXaddChunk<E> next = consumerBuffer.lvNext();
                if (next == null)
                {
                    continue;
                }
                consumerBuffer = next;
            }
            if (consumerBuffer.isPooled())
            {
                if (consumerBuffer.lvSequence(consumerOffset) != chunkIndex)
                {
                    continue;
                }
            } else {
                if (consumerBuffer.lvIndex() != chunkIndex)
                {
                    continue;
                }
            }
            e = consumerBuffer.lvElement(consumerOffset);
        }
        while (e == null && consumerIndex != lvProducerIndex());
        return e;
    }

    @Override
    public Iterator<E> iterator()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public int size()
    {
        return IndexedQueueSizeUtil.size(this);
    }

    @Override
    public int capacity()
    {
        return MessagePassingQueue.UNBOUNDED_CAPACITY;
    }

    @Override
    public boolean relaxedOffer(E e)
    {
        return offer(e);
    }

    @Override
    public E relaxedPoll()
    {
        final int chunkMask = this.chunkMask;
        final int chunkShift = this.chunkShift;
        final int chunkSize = chunkMask + 1;
        final long consumerIndex = this.lvConsumerIndex();
        final MpmcUnboundedXaddChunk<E> consumerBuffer = this.lvConsumerBuffer();
        final int consumerOffset = (int) (consumerIndex & chunkMask);
        final long chunkIndex = consumerIndex >> chunkShift;
        final boolean firstElementOfNewChunk = consumerOffset == 0 && consumerIndex >= chunkSize;
        if (firstElementOfNewChunk)
        {
            final long expectedChunkIndex = chunkIndex - 1;
            final MpmcUnboundedXaddChunk<E> next;
            if (expectedChunkIndex != consumerBuffer.lvIndex() || (next = consumerBuffer.lvNext()) == null)
            {
                return null;
            }
            E e = null;
            final boolean pooled = next.isPooled();
            if (pooled)
            {
                if (next.lvSequence(consumerOffset) != chunkIndex)
                {
                    return null;
                }
            }
            else
            {
                e = next.lvElement(consumerOffset);
                if (e == null)
                {
                    return null;
                }
            }
            if (!casConsumerIndex(consumerIndex, consumerIndex + 1))
            {
                return null;
            }
            if (pooled)
            {
                e = next.lvElement(consumerOffset);
            }
            assert e != null;
            //perform the rotation
            next.soElement(consumerOffset, null);
            next.spPrev(null);
            //save from nepotism
            consumerBuffer.soNext(null);
            if (consumerBuffer.isPooled())
            {
                final boolean offered = freeBuffer.offer(consumerBuffer);
                assert offered;
            }
            //expose next to the other consumers
            soConsumerBuffer(next);
            return e;
        }
        else
        {
            final boolean pooled = consumerBuffer.isPooled();
            E e = null;
            if (pooled)
            {
                final long sequence = consumerBuffer.lvSequence(consumerOffset);
                if (sequence != chunkIndex)
                {
                    return null;
                }
            }
            else
            {
                final long index = consumerBuffer.lvIndex();
                if (index != chunkIndex || (e = consumerBuffer.lvElement(consumerOffset)) == null)
                {
                    return null;
                }
            }
            if (!casConsumerIndex(consumerIndex, consumerIndex + 1))
            {
                return null;
            }
            if (pooled)
            {
                e = consumerBuffer.lvElement(consumerOffset);
                assert e != null;
            }
            assert !pooled ||
                (pooled && consumerBuffer.lvSequence(consumerOffset) == chunkIndex);
            consumerBuffer.soElement(consumerOffset, null);
            return e;
        }
    }

    @Override
    public E relaxedPeek()
    {
        final int chunkMask = this.chunkMask;
        final int chunkShift = this.chunkShift;
        final int chunkSize = chunkMask + 1;
        final long consumerIndex = this.lvConsumerIndex();
        MpmcUnboundedXaddChunk<E> consumerBuffer = this.lvConsumerBuffer();
        final int consumerOffset = (int) (consumerIndex & chunkMask);
        final long chunkIndex = consumerIndex >> chunkShift;
        final boolean firstElementOfNewChunk = consumerOffset == 0 && consumerIndex >= chunkSize;
        if (firstElementOfNewChunk)
        {
            final long expectedChunkIndex = chunkIndex - 1;
            if (expectedChunkIndex != consumerBuffer.lvIndex())
            {
                return null;
            }
            final MpmcUnboundedXaddChunk<E> next = consumerBuffer.lvNext();
            if (next == null)
            {
                return null;
            }
            consumerBuffer = next;
        }
        if (consumerBuffer.isPooled())
        {
            if (consumerBuffer.lvSequence(consumerOffset) != chunkIndex)
            {
                return null;
            }
        }
        else
        {
            if (consumerBuffer.lvIndex() != chunkIndex)
            {
                return null;
            }
        }
        return consumerBuffer.lvElement(consumerOffset);
    }

    @Override
    public int drain(Consumer<E> c)
    {
        return MessagePassingQueueUtil.drain(this, c);
    }

    @Override
    public int fill(Supplier<E> s)
    {
        final int chunkCapacity = chunkMask + 1;
        final int offerBatch = Math.min(PortableJvmInfo.RECOMENDED_OFFER_BATCH, chunkCapacity);
        return MessagePassingQueueUtil.fillInBatchesToLimit(this, s, offerBatch, chunkCapacity);
    }

    @Override
    public int drain(Consumer<E> c, int limit)
    {
        return MessagePassingQueueUtil.drain(this, c, limit);
    }

    @Override
    public int fill(Supplier<E> s, int limit)
    {
        if (null == s)
            throw new IllegalArgumentException("supplier is null");
        if (limit < 0)
            throw new IllegalArgumentException("limit is negative:" + limit);
        if (limit == 0)
            return 0;

        final int chunkShift = this.chunkShift;
        final int chunkMask = this.chunkMask;
        long producerSeq = getAndAddProducerIndex(limit);
        MpmcUnboundedXaddChunk<E> producerBuffer = null;
        for (int i = 0; i < limit; i++)
        {
            final int pOffset = (int) (producerSeq & chunkMask);
            long chunkIndex = producerSeq >> chunkShift;
            if (producerBuffer == null || producerBuffer.lvIndex() != chunkIndex)
            {
                producerBuffer = producerBufferOf(producerBuffer, chunkIndex);
                if (producerBuffer.isPooled())
                {
                    chunkIndex = producerBuffer.lvIndex();
                }
            }
            if (producerBuffer.isPooled())
            {
                while (producerBuffer.lvElement(pOffset) != null)
                {

                }
            }
            producerBuffer.soElement(pOffset, s.get());
            if (producerBuffer.isPooled())
            {
                producerBuffer.soSequence(pOffset, chunkIndex);
            }
            producerSeq++;
        }
        return limit;
    }

    @Override
    public void drain(Consumer<E> c, WaitStrategy wait, ExitCondition exit)
    {
        MessagePassingQueueUtil.drain(this, c, wait, exit);
    }

    @Override
    public void fill(Supplier<E> s, WaitStrategy wait, ExitCondition exit)
    {
        MessagePassingQueueUtil.fill(this, s, wait, exit);
    }

    @Override
    public String toString()
    {
        return this.getClass().getName();
    }

}