package at.wambo.renderer

import scalafx.scene.paint.Color
import akka.actor.{Actor, Props, ActorSystem}
import akka.util.Timeout
import scala.concurrent.duration._
import scala.concurrent.{Await, Future, future}
import akka.pattern.ask
import scala.collection.mutable.ArrayBuffer

/*
 * User: Martin
 * Date: 08.11.13
 * Time: 13:15
 */

class ParallelRayTracer(val imageWidth: Int, val imageHeight: Int, numThreads: Int) extends Renderer {
  val system = ActorSystem("renderer")
  implicit val timeout = Timeout(15 seconds)
  private val colsPerThread = imageHeight / numThreads


  // TODO when an actor is done with its part of the rendering, send him a new part of the scene to render
  def render(scene: Scene): Future[Array[Color]] = {
    val rayTracer = new RayTracer(imageWidth, imageHeight, true)
    val children = (for (i <- 0 until numThreads)
    yield system.actorOf(Props(classOf[RenderActor], rayTracer, scene))).zipWithIndex
    val futures: Seq[Future[RenderResult]] = for {(child, idx) <- children
                                                  startY = colsPerThread * idx
                                                  endY = colsPerThread * (idx + 1)} yield {
      (child ? RenderJob((0, startY), (imageWidth, endY))).mapTo[RenderResult]
    }

    val buffer = ArrayBuffer.empty[(Array[Color], Int)]
    import system.dispatcher
    // TODO Accumulate results in buffer
    // TODO Make sure buffer is in the right order (either sort by start index or do something else)
    // TODO return the above as a Future
    // This currently is synchronous and blocks for every partial result
    for (f <- futures) {
      val result = Await.result(f, 3 minutes)
      val tuple = (result.data, result.start._2)
      buffer += tuple
    }
    // Not sure if I need the sortBy call
    future {
      buffer.sortBy(_._2).flatMap(_._1).toArray
    }
  }

  def close() {
    system.shutdown()
  }
}

sealed trait Message

case class RenderJob(startPos: (Int, Int), endPos: (Int, Int)) extends Message

case class RenderResult(time: Long, start: (Int, Int), end: (Int, Int), data: Array[Color]) extends Message

class RenderActor(rt: RayTracer, scene: Scene) extends Actor {
  def receive = {
    case RenderJob(start, end) => {
      val data = rt.render(scene, start, end)
      sender ! RenderResult(System.nanoTime(), start, end, data)
    }
  }
}