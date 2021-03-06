package org.mapdb.store

import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet
import org.eclipse.collections.impl.stack.mutable.primitive.LongArrayStack
import org.mapdb.*
import org.mapdb.serializer.Serializer
import org.mapdb.util.*
import java.io.*
import java.nio.ByteBuffer
import java.nio.channels.*
import java.nio.file.*
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReadWriteLock


/**
 * Store which serializes its content into primitive `Map<Long,byte[]>`.
 * It optionally persist its content into file, in that case it supports rollback and durability.
 */
open class StoreTrivial(
        override val isThreadSafe:Boolean=true
    ):Store {

    protected val lock: ReadWriteLock? = newReadWriteLock(isThreadSafe)

    protected val closed = AtomicBoolean(false);

    /** stack of deleted recids, those will be reused*/
    //TODO check for duplicates in freeRecids
    protected val freeRecids = LongArrayStack();
    /** maximal allocated recid. All other recids should be in `freeRecid` stack or in `records`*/
    @Volatile  protected var maxRecid:Long = 0;

    /** Stores data */
    protected val records = LongObjectHashMap<ByteArray>();


    companion object {
        private val NULL_RECORD = ByteArray(0);
    }

    fun loadFrom(inStream: InputStream){
        lock.lockWrite{
            loadFromInternal(inStream)
        }
    }

    protected fun loadFromInternal(inStream: InputStream){
        if(CC.PARANOID)
            lock.assertWriteLock()

        fileHeaderCheck(DataInputStream(inStream).readLong())

        var maxRecid2 = 0L;
        freeRecids.clear()
        records.clear();

        //fill recids
        recidLoop@ while (true) {
            val recid = DataIO.unpackLong(inStream)
            if (recid == 0L)
                break@recidLoop
            maxRecid2 = Math.max(maxRecid2, recid)
            var size = DataIO.unpackLong(inStream) - 1
            var data = NULL_RECORD
            if (size >= 0) {
                data = ByteArray((size).toInt())
                DataIO.readFully(inStream, data)
            }

            records.put(recid, data)
        }
        //fill free recids
        for (recid in 1..maxRecid2) {
            if (!records.containsKey(recid))
                freeRecids.push(recid)
        }
        maxRecid = maxRecid2

        Utils.logDebug { "Loaded ${records.size()} objects" }
    }

    protected fun fileHeaderCheck(header:Long){
        if(header.ushr(7*8)!=CC.FILE_HEADER){
            throw DBException.WrongFormat("Wrong file header, not MapDB file")
        }
        if(header.ushr(6*8) and 0xFF!=CC.FILE_TYPE_STORETRIVIAL)
            throw DBException.WrongFormat("Wrong file header, not StoreTrivial file")

        if(header.ushr(4*8) and 0xFFFF != 0L)
            throw DBException.NewMapDBFormat("Store was created with newer format, some new features are not supported")

        if(header and 0xFFFFFFFF != 0L)
            throw DBException.NewMapDBFormat("Store was created with newer format, some new features are not supported")
    }

    protected fun fileHeaderCompose():Long{
        return CC.FILE_HEADER.shl(7*8) + CC.FILE_TYPE_STORETRIVIAL.shl(6*8)
    }

    fun saveTo(outStream: OutputStream) {
        lock.lockRead{
            saveToProtected(outStream)
        }
    }

    protected fun saveToProtected(outStream: OutputStream) {
        //TODO assert protected

        DataOutputStream(outStream).writeLong(fileHeaderCompose())
        val recidIter = records.keySet().longIterator()
        //ByteArray has no equal method, must compare one by one
        while (recidIter.hasNext()) {
            val recid = recidIter.next();
            val bytes = records.get(recid)
            DataIO.packLong(outStream, recid)
            val sizeToWrite: Long =
                    if (bytes === NULL_RECORD) {
                        -1L
                    } else {
                        bytes.size.toLong()
                    }
            DataIO.packLong(outStream, sizeToWrite + 1L)

            if (sizeToWrite >= 0)
                outStream.write(bytes)
        }

        //zero recid marks end
        DataIO.packLong(outStream, 0L)

        Utils.logDebug { "Saved ${records.size()} records" }
    }

    override fun preallocate(): Long {
        lock.lockWrite{
            return preallocateInternal();
        }
    }

    private fun preallocateInternal(): Long {
        if(CC.PARANOID)
            lock.assertWriteLock()

        val recid =
                if (freeRecids.isEmpty)
                    ++maxRecid
                else
                    freeRecids.pop()

        val old = records.put(recid, NULL_RECORD)
        if (old != null)
            throw DBException.DataCorruption("Old data were not null");

        return recid
    }

    override fun <R> put(record: R?, serializer: Serializer<R>): Long {
        val bytes = toByteArray(record, serializer)
        lock.lockWrite{
            val recid = preallocateInternal()
            val old =records.put(recid, bytes)
            if(CC.PARANOID && old!=NULL_RECORD)
                throw AssertionError("wrong preallocation")
            return recid;
        }
    }


    override fun <R> update(recid: Long, record: R?, serializer: Serializer<R>) {
        val bytes = toByteArray(record, serializer)
        lock.lockWrite{
            records.get(recid)
                    ?: throw DBException.GetVoid(recid);

            records.put(recid, bytes)
        }
    }

    override fun <R> compareAndSwap(recid: Long, expectedOldRecord: R?, newRecord: R?, serializer: Serializer<R>): Boolean {
        val expectedOld:ByteArray = toByteArray(expectedOldRecord, serializer)

        //TODO stamped lock?
        lock.lockWrite{
            val old = records.get(recid)
                    ?: throw DBException.GetVoid(recid);

            //handle nulls, compare by reference equality
            if (expectedOldRecord == null && !(old === NULL_RECORD)) {
                return false
            }

            if (!Arrays.equals(expectedOld, old)) {
                return false
            }

            records.put(recid, toByteArray(newRecord, serializer))
            return true
        }
    }

    override fun <R> delete(recid: Long, serializer: Serializer<R>) {
        lock.lockWrite{
            records.get(recid)
                    ?: throw DBException.GetVoid(recid);

            records.remove(recid)
            freeRecids.push(recid)
        }
    }

    override fun commit() {
    }

    override fun compact() {
        //try to minimize maxRecid, and release free recids
        lock.lockWrite{
            val maxRecordRecid = records.keySet().maxIfEmpty(0L)
            val fr = freeRecids.toArray()
            freeRecids.clear()
            for(recid in fr){
                if(recid<maxRecordRecid)
                    freeRecids.push(recid)
            }
            maxRecid = maxRecordRecid
        }
    }

    override fun close() {
        if(closed.compareAndSet(false,true).not())
            return

        if(CC.PARANOID) {
            verify()
        }
    }

    override val isClosed:Boolean
        get()= closed.get()

    override fun <R> get(recid: Long, serializer: Serializer<R>): R? {
        val bytes:ByteArray? =
            lock.lockRead{
                records.get(recid)
            }
        if(bytes===null){
            throw DBException.GetVoid(recid); //does not exist
        }

        if(bytes===NULL_RECORD)
            return null;

        val dataIn = DataInput2.ByteArray(bytes)
        return serializer.deserialize(dataIn, bytes.size)
    }

    fun clear(){
        lock.lockWrite{
            clearInternal()
        }
    }

    internal fun clearInternal(){
        if(CC.PARANOID)
            lock.assertWriteLock()
        records.clear()
        freeRecids.clear()
        maxRecid = 0
    }

    private fun <R> toByteArray(record: R?, serializer: Serializer<R>): ByteArray {
        if(record === null)
            return NULL_RECORD
        val out = DataOutput2()
        serializer.serialize(out, record)
        return out.copyBytes();
    }


    override fun equals(other: Any?): Boolean {
        if (other !is StoreTrivial)
            return false

        if(this===other)
            return true;

        lock.lockRead() {
            if (records.size() != other.records.size())
                return false


            val recidIter = records.keySet().longIterator()
            //ByteArray has no equal method, must compare one by one
            while (recidIter.hasNext()) {
                val recid = recidIter.next();
                val b1 = records.get(recid)
                val b2 = other.records.get(recid)

                if (b1 === b2)
                    continue

                if (b1 !== b2 && !Arrays.equals(b1, b2)) {
                    return false
                }

                if (b1 === NULL_RECORD)
                    return false;
            }

            return freeRecids.equals(other.freeRecids)
        }
    }


    override fun getAllRecids(): LongIterator {
        lock.lockRead{
            return records.keySet().toArray().iterator()
        }
    }

    override fun verify() {
        lock.lockRead{
            freeRecids.forEach { recid ->
                if ((records.containsKey(recid)))
                    throw AssertionError("free recid is present")
                if(recid>maxRecid)
                    throw AssertionError("max recid")
            }
            records.keySet().forEach{ recid ->
                if(recid>maxRecid)
                    throw AssertionError("max recid")
            }
        }

        lock.lockRead{
            val freeRecidsSet = LongHashSet();
            freeRecidsSet.addAll(freeRecids)
            for (recid in 1..maxRecid) {//TODO put assertions for underlying collections and Volumes

                if (!freeRecidsSet.contains(recid) && !records.containsKey(recid))
                    throw IllegalStateException("Recid not used " + recid);
            }
        }

    }

    override val isReadOnly = false

    override fun fileLoad() = false

    open override fun getAllFiles(): Iterable<String> {
        return arrayListOf()
    }

}

class StoreTrivialTx(val file:File, isThreadSafe:Boolean=true, val deleteFilesAfterClose:Boolean=false)
    :StoreTrivial(
        isThreadSafe = isThreadSafe
    ), StoreTx{

    val path = file.toPath()

    companion object{
        internal val COMMIT_MARKER_SUFFIX = ".c";
        internal val DATA_SUFFIX = ".d";
    }

    private val fileChannel: FileChannel =
                FileChannel.open(path, StandardOpenOption.READ,
                        StandardOpenOption.WRITE, StandardOpenOption.CREATE)

    private val fileLock: FileLock =
                try {
                    fileChannel.tryLock()
                }catch(e: OverlappingFileLockException) {
                    throw DBException.FileLocked(path!!, e)
                }

    private var lastFileNum:Long = -1

    init{
        lock.lockWrite{
            val buf = ByteBuffer.allocate(8)
            if(fileChannel.size()>0L) {
                fileChannel.read(buf, 0L)
                val header = buf.getLong(0)
                fileHeaderCheck(header)

                Utils.logDebug { "Opened file ${path}" }
                val lattest = findLattestCommitMarker()
                lastFileNum = lattest ?: -1L;
                if (lattest != null) {
                    loadFrom(lattest);
                }
            }else{
                //TODO protected by C marker
                val header = fileHeaderCompose()
                buf.putLong(0, header)
                fileChannel.write(buf, 0L)
                fileChannel.force(true)
            }
        }
    }
    internal  fun findLattestCommitMarker():Long?{
        lock.assertReadLock()
        if(null == path)
            return null

        var highestCommitNumber = -1L;

        val name = path.fileName!!.toString()

        for(child in Files.list(path.parent)){
            if(!Files.isRegularFile(child))
                continue
            val cname = child.fileName!!.toString()
            if(!cname.startsWith(name))
                continue
            if(!cname.endsWith(COMMIT_MARKER_SUFFIX))
                continue;

            //parse number
            val splited = cname.toString().split('.');
            try {
                val commitNumber = java.lang.Long.valueOf(splited[splited.size - 2])
                if(commitNumber>highestCommitNumber){
                    highestCommitNumber = commitNumber
                }
            }catch(e:NumberFormatException){
                //not a number, ignore this file
                continue
            }
        }

        return if(highestCommitNumber==-1L)
               null
            else
                highestCommitNumber
    }


    protected fun loadFrom(number:Long){
        if(CC.PARANOID)
            lock.assertWriteLock()
        val readFrom = Utils.pathChangeSuffix(path, "."+number + DATA_SUFFIX)

        Utils.logDebug { "Loading from ${readFrom} with length ${readFrom.toFile().length()}" }
        Files.newInputStream(readFrom, StandardOpenOption.READ).buffered().use {
            loadFromInternal(it)
        }
    }

    override fun commit() {
        lock.lockWrite{
            val prev = lastFileNum;
            val next = prev + 1;

            //save to file
            val saveTo = Utils.pathChangeSuffix(path, "." + next + DATA_SUFFIX)
            //TODO provide File.newOutput... method protected by C marker
            //TODO write using output stream should call FD.sync() at end
            Files.newOutputStream(saveTo, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE, StandardOpenOption.WRITE).buffered().use {
                saveToProtected(it)
            }
            //create commit marker
            Files.createFile(Utils.pathChangeSuffix(path, "." + next + COMMIT_MARKER_SUFFIX))
            lastFileNum = next
            //delete old data
            Files.deleteIfExists(Utils.pathChangeSuffix(path, "." + prev + COMMIT_MARKER_SUFFIX))
            Files.deleteIfExists(Utils.pathChangeSuffix(path, "." + prev + DATA_SUFFIX))

            Utils.logDebug { "Committed into ${saveTo} with length ${saveTo.toFile().length()}" }
        }
    }

    override fun rollback() {
        lock.lockWrite{
            if(lastFileNum==-1L){
                //no commit was made yet, revert to empty state
                clearInternal()
                return
            }
            loadFrom(lastFileNum)
        }
    }

    override fun close() {
        if(CC.PARANOID)
            verify()

        lock.lockWrite{
            if(closed.compareAndSet(false,true).not())
                return

            fileLock.release();
            fileChannel.close()
            if(deleteFilesAfterClose){
                val f = file.path
                for(i in 0 .. lastFileNum){
                    for(suffix in arrayOf(COMMIT_MARKER_SUFFIX,DATA_SUFFIX)){
                        File(f+"."+i+suffix).delete()
                    }
                }
                file.delete()
            }
        }
    }


    override fun getAllFiles(): Iterable<String> {
        return arrayListOf(file.path)
    }
}