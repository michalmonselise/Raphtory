package com.raphtory.core.actors.AnalysisManager.Tasks.ViewTasks

import com.raphtory.core.analysis.api.Analyser
import com.raphtory.core.actors.AnalysisManager.Tasks.AnalysisTask
import com.raphtory.core.model.communication.AnalysisType

class ViewAnalysisTask(managerCount:Int, jobID: String,args:Array[String], analyser: Analyser[Any], time: Long,newAnalyser:Boolean,rawFile:String)
        extends AnalysisTask(jobID: String, args, analyser,managerCount,newAnalyser,rawFile) {
  override def timestamp(): Long = time

  override protected def analysisType(): AnalysisType.Value = AnalysisType.view

  override def restart(): Unit = {
    println(s"View Analysis manager for $jobID at ${time} finished")
    //killme()
  }

  override def processResults(timestamp: Long): Unit =
    analyser.extractResults(result.toArray)
}