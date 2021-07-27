import scala.io.Source
import com.raphtory.core.actors.Router.GraphBuilder
import com.raphtory.core.model.communication.{EdgeAdd, ImmutableProperty, Properties, Type}
import com.raphtory.core.actors.Spout.Spout

import scala.collection.mutable

class BenchmarkSpout extends Spout[String] {
  val fileQueue = mutable.Queue[String]()
  override def setupDataSource(): Unit = {

    fileQueue ++=
      scala.io.Source.fromFile("./data/college_seq.csv")
        .getLines
  }

  override def generateData(): Option[String] = {
    if(fileQueue isEmpty){
      dataSourceComplete()
      None
    }
    else
      Some(fileQueue.dequeue())
  }

  override def closeDataSource(): Unit = {}
}



