package rescala.pipelining

import rescala.turns.Turn
import scala.collection.immutable.Queue
import scala.annotation.tailrec

import rescala.graph._

class ValueHolder[T](initial: T, val buffer: PipelineSingleBuffer[T]) {

  var value: T = initial
  var isChanged = false

  def transform(f: T => T) = value = f(value)

  def duplicate(newTurn: Turn) = {
    val duplicate = new ValueHolder(value, buffer)
    if (buffer.takePrevious) {
      newTurn.schedule(buffer)
    }
    duplicate
  }

}

class BufferFrameContent {

  var values: List[ValueHolder[_]] = List()

  def valueForBuffer[T](buf: PipelineSingleBuffer[T]): ValueHolder[T] = values.find { _.buffer eq buf }.get.asInstanceOf[ValueHolder[T]] // Cast is safe

  def duplicate(newTurn: Turn) = {
    val newContent = new BufferFrameContent
    for (v <- values) {
      newContent.values :+= v.duplicate(newTurn)
    }
    newContent
  }

}

class PipelineSingleBuffer[A](parent: PipelineBuffer, initialStrategy: (A, A) => A, val takePrevious: Boolean) extends Buffer[A] {

  var commitStrategy: (A, A) => A = initialStrategy

  override def initCurrent(value: A): Unit = parent.getStableFrame().valueForBuffer(this).value = value
  override def initStrategy(strategy: (A, A) => A): Unit = synchronized(commitStrategy = strategy)

  override def transform(f: (A) => A)(implicit turn: Turn): A = synchronized {
    implicit val pTurn = turn.asInstanceOf[PipeliningTurn]
    val value = f(get)
    set(value)
    value
  }

  private def set(value: A, silent: Boolean)(implicit turn: Turn): Unit = {
    implicit val pTurn = turn.asInstanceOf[PipeliningTurn]
    val frame =  parent.needFrame()
    assert(!frame.isWritten)
    val valueHolder =frame.content.valueForBuffer(this)
    valueHolder.value = value
    valueHolder.isChanged = true
      if (!silent) 
       println(s"${Thread.currentThread().getId}: SET to $value for $turn at ${parent.reactive}")
    
  }

  override def set(value: A)(implicit turn: Turn): Unit = {
    implicit val pTurn = turn.asInstanceOf[PipeliningTurn]
    set(value, false)
    turn.schedule(this)
  }

  override def base(implicit turn: Turn): A = {
    implicit val pTurn = turn.asInstanceOf[PipeliningTurn]
    if (! takePrevious) {
    parent.findFrame {
      _ match {
        case Some(frame) =>
          println(s"${Thread.currentThread().getId} has already new value for $turn = ${frame.content.valueForBuffer(this).isChanged} at ${parent.reactive}")
          val content = if (frame.content.valueForBuffer(this).isChanged)
            if (frame.previous() == null)
              parent.getStableFrame()
            else
              frame.previous().content
          else
            frame.content
          content.valueForBuffer(this).value
        case None =>
          parent.frame().valueForBuffer(this).value

      }
    }
    } else {
       parent.findFrame {
        _ match {
          case Some(frame) =>
           
            val content = if (frame.previous() == null)
                parent.getStableFrame()
              else
                frame.previous().content
           
            content.valueForBuffer(this).value
          case None =>
            parent.frame().valueForBuffer(this).value
        }
      }
    }
  }
  override def get(implicit turn: Turn): A = {
    implicit val pTurn = turn.asInstanceOf[PipeliningTurn]

    if (!takePrevious) parent.frame().valueForBuffer(this).value else {

      parent.findFrame {
        _ match {
          case Some(frame) =>
            val hasValue = frame.content.valueForBuffer(this).isChanged || frame.isWritten
            val content = if (!hasValue) {
                println(s"${Thread.currentThread().getId}: GET WITHOUT VALUE from ${parent.reactive} from frame ${frame.turn} ")
              if (frame.previous() == null)
                parent.getStableFrame()
              else
                frame.previous().content
            } else {
              println(s"${Thread.currentThread().getId}: GET WITH VALUE from ${parent.reactive} from frame ${frame.turn}")
              frame.content
            }
            content.valueForBuffer(this).value
          case None =>
                println(s"${Thread.currentThread().getId}: GET WITHOUT FRAME")
            parent.frame().valueForBuffer(this).value
        }
      }
    }
  }

  override def release(implicit turn: Turn): Unit = {
    implicit val pTurn = turn.asInstanceOf[PipeliningTurn]
    val frame = parent.needFrame()
  //  if (!frame.isWritten) {
  //    assert(frame.isWritten, s"Release buffer for ${parent.reactive} but is not written")
  //  }
    frame.content.valueForBuffer(this).isChanged = false
  }

  override def commit(implicit turn: Turn): Unit = {
    implicit val pTurn = turn.asInstanceOf[PipeliningTurn]
    // current = commitStrategy(current, get)
    // release(turn)
    val requiredValue = if (parent.needFrame().content.valueForBuffer(this).isChanged) {
      val oldValue = base
      val currentValue = get
      val commitValue = commitStrategy(oldValue, currentValue)
      // println(s"${parent.reactive}: COMMIT $oldValue -> $currentValue = $commitValue at $turn")
      set(commitValue, true)
      commitValue
    } else {
      parent.needFrame().content.valueForBuffer(this).isChanged = true
      parent.waitUntilCanWrite
      val oldValue = base
    //   println(s"${Thread.currentThread().getId}: COMMIT for previous $oldValue at $turn at ${parent.reactive}")
      val commitValue = commitStrategy(oldValue, oldValue)
      set(commitValue, true)
      commitValue
    }
    release
   // assert(get == requiredValue)
  }

  override def toString() = s"PipelineBuffer(${parent.reactive})"
  
}

object PipelineBuffer {
  protected[pipelining] def pipelineFor(at: Reactive) = at.pipeline
}

class PipelineBuffer(val reactive: Reactive) {

  protected[this]type Content = BufferFrameContent;

  private type CFrame = Frame[Content]

  protected[this] def initialStableFrame: Content = new BufferFrameContent
  protected[this] def duplicate(content: Content, newTurn: Turn): Content = content.duplicate(newTurn)
  protected[this] var stableFrame: Content = initialStableFrame
  protected[rescala] def getStableFrame() = stableFrame

  protected[this] var queueHead: CFrame = null.asInstanceOf[CFrame];
  protected[this] var queueTail: CFrame = null.asInstanceOf[CFrame];

  private object pipelineLock

  private def lockPipeline[A](op: => A): A = pipelineLock.synchronized {
    op
  }
  
  protected[pipelining] var createdBuffers : Set[PipelineSingleBuffer[_]] = Set()

  protected[pipelining] def createBuffer[T](initval: T, commitStrategy: (T, T) => T, takePrevious: Boolean): PipelineSingleBuffer[T] = {
    assert(queueHead == null)
    val newBuffer = new PipelineSingleBuffer(this, commitStrategy, takePrevious)
    stableFrame.values :+= new ValueHolder(initval, newBuffer)
    createdBuffers += newBuffer
    newBuffer
  }

  // Access for testing
  protected[rescala] final def getPipelineFrames() = lockPipeline {
    def makeQueue(head: CFrame, queue: Queue[CFrame]): Queue[CFrame] = {
      if (head == null)
        queue
      else
        makeQueue(head.next(), queue :+ head)
    }
    makeQueue(queueHead, Queue())
  }

  protected[rescala] def findFrame[T](find: Option[CFrame] => T)(implicit turn: PipeliningTurn): T = lockPipeline {
    @tailrec
    def findFrame(head: CFrame): Option[CFrame] = {
      if (head == null)
        None
      else if (head.turn eq turn)
        Some(head)
      else findFrame(head.next)
    }
    val selectedFrame = findFrame(queueHead)
    find(selectedFrame)
  }

  private def findFrame[T](found: CFrame => T, notFound: => T)(implicit turn: PipeliningTurn): T = {
    findFrame(_ match {
      case Some(d) => found(d)
      case None    => notFound
    })
  }

  protected[rescala] def needFrame[T](op: CFrame => T = { x: CFrame => x })(implicit turn: PipeliningTurn): T = {
    findFrame(_ match {
      case Some(d) => op(d)
      case None    => throw new AssertionError(s"No frame found for $turn at $this")
    })
  }

  private def frame(implicit turn: PipeliningTurn): Option[CFrame] = lockPipeline {
    @tailrec
    def findBottomMostFrame(tail: CFrame): Option[CFrame] = {
      if (tail == null)
        None
      else if (turn > tail.turn)
        Some(tail)
      else
        findBottomMostFrame(tail.previous())
    }

    // Are not allowed to hold the pipelineLock, because than we cannot
    // query for the graph lock, to prohibit deadlocks

    // Local: if the turn itself is found, it is the bottom most frame => no need to sync
    val bottomMostWaitingFrame: Option[CFrame] = findFrame(x => x).orElse(findBottomMostFrame(queueTail))
    bottomMostWaitingFrame
  }

  protected[pipelining] def frame[T](f: Content => T = { x: Content => x })(implicit turn: PipeliningTurn): T = {
    val content = frame.map(_.content).getOrElse(stableFrame)
    f(content)
  }

  protected[rescala] def waitUntilCanWrite(implicit turn: PipeliningTurn): Unit = {
    findFrame(x => x) match {
      case Some(turnFrame) => turnFrame.awaitPredecessor(pipelineLock, turn)
      case None            => throw new AssertionError(s"No frame for $turn at $this")
    }
  }

  protected[rescala] def waitUntilCanRead(implicit turn: PipeliningTurn): Unit = {
    // TODO IF keep frame reordering, need to do something more here. because the frame
    // we need to read from may change

   // println(s"${Thread.currentThread().getId} with turn $turn waits until read for ${this.reactive}")
    frame match {
      case Some(frame) => frame match {
        case WriteFrame(_, _) =>
          if (frame.turn eq turn) {
            
           //   println(s"${Thread.currentThread().getId} own write frame")
              frame.awaitPredecessor(pipelineLock, turn)
            
          } else {
         //   println(s"${Thread.currentThread().getId} write frame for ${frame.turn}")
            assert(turn > frame.turn)
            frame.awaitUntilWritten(turn)
          }
        case _ => if (frame.turn eq turn) {
     //      println(s"${Thread.currentThread().getId} own dynamic frame")
          frame.awaitPredecessor(pipelineLock, turn)
        } else { 
    //      println(s"${Thread.currentThread().getId} dynamic frame for ${frame.turn}")
          frame.awaitUntilWritten(turn) 
          }
      }
      case None =>
    }
  }

  protected[rescala] def hasFrame(implicit turn: PipeliningTurn): Boolean = {
    findFrame(_ => true, false)
  }

  protected[rescala] def writeFramesAfter(frame: CFrame): List[WriteFrame[Content]] = lockPipeline {
    def collectFrames(head: CFrame = frame): List[WriteFrame[Content]] = {
      if (head == null)
        List()
      else
        head match {
          case (writeFrame @ WriteFrame(_, _)) => writeFrame :: collectFrames(head.next())
          case _                               => collectFrames(head.next())
        }
    }

    collectFrames()
  }

  protected[rescala] def createFrame(implicit turn: PipeliningTurn): Unit = lockPipeline {
    assert(!hasFrame)
    def createFrame(prev: Content): CFrame = {
      val newFrame = WriteFrame[Content](turn, this)
      newFrame.content = duplicate(prev, turn)
      assert(newFrame.turn == turn)
      newFrame
    }

    if (queueHead == null) {
      queueHead = createFrame(stableFrame)
      queueTail = queueHead
    } else {
      val newFrame = createFrame(queueTail.content)
      newFrame.insertAfter(queueTail)
      queueTail = newFrame
    }
    assert(hasFrame)
  }

  protected[rescala] def createFrameBefore(stillBefore: PipeliningTurn => Boolean)(implicit turn: PipeliningTurn): Unit = lockPipeline {
    assert(!hasFrame)
    def createFrame(prev: Content): CFrame = {
      val newFrame = WriteFrame[Content](turn, this)
      newFrame.content = duplicate(prev, turn)
      assert(newFrame.turn == turn)
      newFrame
    }

    def assertNoOtherFrameBefore(tail: CFrame): Boolean = {
      if (tail == null)
        true
      else if (stillBefore(tail.turn))
        false
      else assertNoOtherFrameBefore(tail.previous())
    }

    def findPreviousFrame(tail: CFrame = queueTail): CFrame = {
      if (tail == null)
        null
      else if (stillBefore(tail.turn))
        findPreviousFrame(tail.previous())
      else {
        assert(assertNoOtherFrameBefore(tail.previous()))
        tail
      }
    }

    if (queueTail == null) {
      queueHead = createFrame(stableFrame)
      queueTail = queueHead
    } else {
      val insertAfter = findPreviousFrame()
      if (insertAfter == null) {
        val newFrame = createFrame(stableFrame)
        queueHead.insertAfter(newFrame)
        queueHead = newFrame
      } else {
        val newFrame = createFrame(insertAfter.content)
        newFrame.insertAfter(insertAfter)
        if (queueTail == insertAfter) {
          queueTail = newFrame
        }
      }
    }
    assert(hasFrame)
  }

  protected[rescala] def foreachFrameTopDown(action: CFrame => Unit): Unit = {
    @tailrec
    def impl(head: CFrame = queueHead): Unit = {
      if (head != null) {
        action(head)
        impl(head.next())
      }
    }
    impl()
  }

  protected[rescala] def insertWriteFrameFor(otherTurn: PipeliningTurn)(implicit turn: PipeliningTurn): Unit = {
    lockPipeline {
      assert(queueHead != null, s"At least the frame for $turn needs to be there")

      @tailrec
      def findFrameToInsertAfter(last: CFrame = queueTail): CFrame = {
        assert(last != null, s"No frame found, but at least frame for $turn should be there")
        val frameTurn = last.turn
        if (frameTurn == turn) {
          last
        } else if (otherTurn > frameTurn) {
          last
        } else {
          findFrameToInsertAfter(last.previous())
        }
      }

      val preceedingFrame = findFrameToInsertAfter()

      val newFrame = WriteFrame[Content](otherTurn, this)
      newFrame.content = duplicate(preceedingFrame.content, otherTurn)
      newFrame.insertAfter(preceedingFrame)
    }
  }

  protected[rescala] def fillFrame(implicit turn: PipeliningTurn): Unit = lockPipeline {

    @tailrec
    def refreshFrame(head: CFrame): Unit = {
      if (head == null) {
        throw new AssertionError(s"No frame found for turn $turn")
      } else if (head.turn == turn) {
        val newContent = duplicate(if (head.previous() == null) stableFrame else head.previous().content, turn)
        head.content = newContent
      } else {
        refreshFrame(head.next())
      }
    }
    refreshFrame(queueHead)
  }

  protected[rescala] def removeFrame(implicit turn: PipeliningTurn): Unit = lockPipeline {
    // Can remote the frame if it is head of the queue
    if (queueHead.turn == turn) {
      val newHead = queueHead.next()
      val newTail = if (newHead == null) null.asInstanceOf[CFrame] else queueTail
      queueHead.removeFrame()
      stableFrame = queueHead.content
      queueHead = newHead
      queueTail = newTail
    } else {
      //  println(s"Mark remove $turn at $this")
      assert(false, s"Frame for $turn cannot be removed at $this because it is not head of the queue: ${getPipelineFrames()}")
    }
  }

  protected[rescala] def markWritten(implicit turn: PipeliningTurn): Unit = {
    needFrame(_.markWritten())
  }

  protected[rescala] def markTouched(implicit turn: PipeliningTurn): Unit = {
    needFrame(_.markTouched())
  }

  protected[rescala] def createDynamicFrame[T <: CFrame](makeFrame: => T)(from: Reactive)(implicit turn: PipeliningTurn): T = {
    assert(!hasFrame)
    val predeceedingFrameOpt: Option[CFrame] = frame
    lockPipeline {
      val readFrame = makeFrame
      predeceedingFrameOpt match {
        case Some(predecessor) =>
          readFrame.content = duplicate(predecessor.content, turn)
          val insertAtEnd = predecessor == queueTail
          readFrame.insertAfter(predecessor)
          if (insertAtEnd)
            queueTail = readFrame
        case None =>
          readFrame.content = duplicate(stableFrame, turn)
          if (queueHead == null) {
            queueHead = readFrame
            queueTail = queueHead
          } else {
            queueHead.insertAfter(readFrame)
            queueHead = readFrame
          }
      }
      readFrame
    }
  }

  protected[rescala] def createDynamicReadFrame(from: Reactive)(implicit turn: PipeliningTurn): DynamicReadFrame[Content] = {
    createDynamicFrame(DynamicReadFrame[Content](turn, this, from))(from)
  }

  protected[rescala] def createDynamicDropFrame(from: Reactive)(implicit turn: PipeliningTurn): DynamicDropFrame[Content] = {
    createDynamicFrame(DynamicDropFrame[Content](turn, this, from))(from)
  }

}