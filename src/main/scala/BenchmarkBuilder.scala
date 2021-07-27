import scala.io.Source
import com.raphtory.core.model.communication._
import com.raphtory.core.actors.Router.GraphBuilder
import com.raphtory.core.model.communication._


import scala.collection.mutable

class BenchmarkBuilder extends GraphBuilder[String]{

  override def parseTuple(tuple: String) = {
    val fileLine   = tuple.split(',')
    val sourceNode = fileLine(0)
    val srcID: Long = assignID(sourceNode)
    val targetNode = fileLine(1)
    val tarID: Long = assignID(targetNode)
    val timeStamp: Long = fileLine(2).toLong


    val properties: Properties = Properties(ImmutableProperty("name", sourceNode))
    val characterType: Type = Type("Character")
    addVertex(timeStamp, srcID, properties,characterType)
    val targetNodeProperties: Properties = Properties(ImmutableProperty("name", targetNode))
    addVertex(timeStamp, tarID, targetNodeProperties,characterType)
    addEdge(timeStamp,srcID,tarID, Type("Interaction"))
  }

}
