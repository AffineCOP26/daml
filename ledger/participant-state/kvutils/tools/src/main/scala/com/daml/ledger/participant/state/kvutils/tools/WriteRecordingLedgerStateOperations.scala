package com.daml.ledger.participant.state.kvutils.tools

import com.daml.ledger.participant.state.kvutils.export.LedgerDataExport.WriteSet
import com.daml.ledger.validator.LedgerStateOperations
import com.daml.ledger.validator.LedgerStateOperations.{Key, Value}

import scala.collection.mutable.ListBuffer
import scala.concurrent.Future

class WriteRecordingLedgerStateOperations[LogResult](delegate: LedgerStateOperations[LogResult])
    extends LedgerStateOperations[LogResult] {
  private val recordedWriteSet = ListBuffer.empty[(Key, Value)]

  override def readState(key: Key): Future[Option[Value]] = delegate.readState(key)

  override def readState(keys: Seq[Key]): Future[Seq[Option[Value]]] = delegate.readState(keys)

  override def writeState(key: Key, value: Value): Future[Unit] = {
    this.synchronized(recordedWriteSet.append((key, value)))
    delegate.writeState(key, value)
  }

  override def writeState(keyValuePairs: Seq[(Key, Value)]): Future[Unit] = {
    this.synchronized(recordedWriteSet.appendAll(keyValuePairs))
    delegate.writeState(keyValuePairs)
  }

  override def appendToLog(key: Key, value: Value): Future[LogResult] = {
    this.synchronized(recordedWriteSet.append((key, value)))
    delegate.appendToLog(key, value)
  }

  def getAndClearRecordedWriteSet(): WriteSet = {
    this.synchronized {
      val result = Seq(recordedWriteSet: _*)
      recordedWriteSet.clear()
      result
    }
  }
}
