package edu.gemini.lchquery.servlet

import edu.gemini.pot.sp.{ISPObservation, ISPProgram}
import edu.gemini.spModel.`type`.DisplayableSpType
import edu.gemini.spModel.ao.{AOConstants, AOTreeUtil}
import edu.gemini.spModel.core.ProgramId
import edu.gemini.spModel.core.ProgramId.Science
import edu.gemini.spModel.data.YesNoType
import edu.gemini.spModel.dataset.{DatasetLabel, DatasetQaRecord}
import edu.gemini.spModel.gemini.altair.{AltairParams, InstAltair}
import edu.gemini.spModel.gemini.obscomp.{SPProgram, SPSiteQuality}
import edu.gemini.spModel.gemini.obscomp.SPProgram.Active
import edu.gemini.spModel.gemini.phase1.GsaPhase1Data
import edu.gemini.spModel.obs.{ObsClassService, ObsTargetCalculatorService, ObservationStatus, SPObservation}
import edu.gemini.spModel.obscomp.SPInstObsComp
import edu.gemini.spModel.obslog.ObsLog
import edu.gemini.spModel.target.env.TargetEnvironment
import edu.gemini.spModel.target.obsComp.TargetObsComp
import edu.gemini.spModel.too.Too
import edu.gemini.spModel.util.SPTreeUtil

import scala.collection.JavaConverters._
import scala.util.matching.Regex
import scalaz._
import Scalaz._


sealed trait ValueMatcher[A] {
  def matches(expression: String, x: A): Boolean
}

object ValueMatcher {
  private[servlet] implicit class ToRegex(val expression: String) extends AnyVal {
    def toRegex: Regex = ("^(?i)" +
      (expression.contains("|") ? s"($expression)" | expression).
        replaceAllLiterally("+", "\\+").
        replaceAllLiterally("*", ".*").
        replaceAllLiterally("%", ".*").
        replaceAllLiterally("?", ".")
      + "$").r
  }

  abstract class StringValueMatcher[A] extends ValueMatcher[A] {
    protected def extractor(a: A): Option[String]

    override def matches(expression: String, x: A): Boolean = (for {
      r <- Option(expression).map(_.toRegex)
      m <- Option(x).flatMap(extractor)
    } yield r.findFirstMatchIn(m).isDefined).getOrElse(false)
  }


  abstract class BooleanValueMatcher[A, B <: DisplayableSpType] extends ValueMatcher[A] {
    protected def extractor(a: A): Option[B]

    private def transform(expression: String): String = expression.toLowerCase match {
      case "true" => YesNoType.YES.displayValue()
      case "false" => YesNoType.NO.displayValue()
      case _ => expression
    }

    override def matches(expression: String, x: A): Boolean = (for {
      r <- Option(expression).map(transform).map(_.toRegex)
      m <- Option(x).flatMap(extractor).map(_.displayValue)
    } yield r.findFirstMatchIn(m).isDefined).getOrElse(false)
  }
}


sealed case class LchQueryParam[A](name: String, v: ValueMatcher[A])

object LchQueryParam {
  import ValueMatcher._

  private[servlet] implicit class ToYesNo(val b: Boolean) extends AnyVal {
    def toYesNo: YesNoType =
      b ? YesNoType.YES | YesNoType.NO
  }


  private[servlet] implicit class ISPProgramExtractors(val prog: ISPProgram) extends AnyVal {
    def toSPProg: Option[SPProgram] =
      Option(prog).map(_.getDataObject.asInstanceOf[SPProgram])

    def scienceSemester: Option[String] =
      ProgramId.parse(prog.getProgramID.toString) match {
        case Science(_, sem, _, _) => Some(sem.toString)
        case _                     => None
      }

    def investigatorNames: Option[List[String]] = {
      def investigatorName(inv: GsaPhase1Data.Investigator): Option[String] =
        Option(inv).map(i => s"${i.getFirst} ${i.getLast}").filterNot(_.trim.isEmpty)

      toSPProg.map { spProg =>
        (investigatorName(spProg.getGsaPhase1Data.getPi) :: spProg.getGsaPhase1Data.getCois.asScala.map(investigatorName).toList).
          collect { case Some(i) => i }
      }
    }

    def coIEmails: Option[List[String]] =
      toSPProg.map(_.getGsaPhase1Data.getCois.asScala.toList.map(_.getEmail).filterNot(_.isEmpty))

    def abstrakt: Option[String] =
      toSPProg.map(_.getGsaPhase1Data.getAbstract.toString)

    def scienceBand: Option[String] =
      toSPProg.map(_.getQueueBand)

    def partners: Option[List[String]] =
      toSPProg.map(_.getTimeAcctAllocation.getCategories.asScala.toList.map(_.getDisplayName))

    def tooStatus: Option[YesNoType] =
      toSPProg.map(_.isToo.toYesNo)

    // Allocated time in ms.
    def allocatedTime: Long =
      toSPProg.map(spProg => (spProg.getTimeAcctAllocation.getTotalTime * 60 * 60 * 1000).toLong).getOrElse(0)

    // Remaining time in ms.
    def remainingTime: Long =
      prog.getAllObservations.asScala.map(ObsTargetCalculatorService.calculateRemainingTime).sum
  }


  private[servlet] implicit class ISPObservationExtractors(val obs: ISPObservation) extends AnyVal {
    def toSPObs: Option[SPObservation] =
      Option(obs).map(_.getDataObject.asInstanceOf[SPObservation])

    def instrumentType: Option[String] =
      SPTreeUtil.findInstruments(obs).asScala.map(_.getDataObject).collect {
        case inst: SPInstObsComp => inst.getType.readableStr
      }.headOption

    def ao: AOConstants.AO = {
      Option(AOTreeUtil.findAOSystem(obs)).map(_.getDataObject).
        collect {
          case inst: InstAltair if inst.getGuideStarType == AltairParams.GuideStarType.LGS => AOConstants.AO.Altair_LGS
          case inst: InstAltair if inst.getGuideStarType == AltairParams.GuideStarType.NGS => AOConstants.AO.Altair_NGS
        }.getOrElse(AOConstants.AO.NONE)
    }

    def targetEnvironment: Option[TargetEnvironment] = for {
      n <- Option(SPTreeUtil.findTargetEnvNode(obs))
      c <- Option(n.getDataObject).map(_.asInstanceOf[TargetObsComp])
      t <- Option(c.getTargetEnvironment)
    } yield t

    def timingWindows: Option[List[SPSiteQuality.TimingWindow]] = for {
      n <- Option(SPTreeUtil.findObsCondNode(obs))
      c <- Option(n.getDataObject).map(_.asInstanceOf[SPSiteQuality])
    } yield c.getTimingWindows.asScala.toList

    def obsLogComments: Option[Map[DatasetLabel,DatasetQaRecord]] =
      Option(ObsLog.getIfExists(obs)).map(_.getQaRecord.qaMap.filterNot {
        case (_,rec) => rec.comment.isEmpty
      })
  }



  private[servlet] val ProgramSemesterParam = LchQueryParam("programSemester",
    new StringValueMatcher[ISPProgram] {
      override protected def extractor(prog: ISPProgram): Option[String] =
        prog.scienceSemester
    }
  )

  private[servlet] val ProgramTitleParam = LchQueryParam("programTitle",
    new StringValueMatcher[ISPProgram] {
      override protected def extractor(prog: ISPProgram): Option[String] =
        prog.toSPProg.map(_.getTitle)
    }
  )

  private[servlet] val ProgramReferenceParam = LchQueryParam("programReference",
    new StringValueMatcher[ISPProgram] {
      override protected def extractor(prog: ISPProgram): Option[String] =
        Option(prog).map(_.getProgramID.stringValue)
    }
  )

  private[servlet] val ProgramActiveParam = LchQueryParam("programActive",
    new BooleanValueMatcher[ISPProgram,SPProgram.Active] {
      override protected def extractor(prog: ISPProgram): Option[Active] =
        prog.toSPProg.flatMap(sp => Option(sp.getActive))
    }
  )

  private[servlet] val ProgramCompletedParam = LchQueryParam("programCompleted",
    new BooleanValueMatcher[ISPProgram,YesNoType] {
      override protected def extractor(prog: ISPProgram): Option[YesNoType] =
        prog.toSPProg.map(_.isCompleted.toYesNo)
    }
  )

  private[servlet] val ProgramNotifyPIParam = LchQueryParam("programNotifyPi",
    new BooleanValueMatcher[ISPProgram,YesNoType] {
      override protected def extractor(prog: ISPProgram): Option[YesNoType] =
        prog.toSPProg.map(_.getNotifyPi)
    }
  )

  private[servlet] val ProgramRolloverParam = LchQueryParam("programRollover",
    new BooleanValueMatcher[ISPProgram,YesNoType] {
      override protected def extractor(prog: ISPProgram): Option[YesNoType] =
        prog.toSPProg.map(_.getRolloverStatus.toYesNo)
    }
  )

  private[servlet] val ObservationTOOStatusParam = LchQueryParam("observationTooStatus",
    new StringValueMatcher[ISPObservation] {
      override protected def extractor(obs: ISPObservation): Option[String] =
        Option(obs).map(o => Too.get(o).getDisplayValue)
    }
  )

  private[servlet] val ObservationNameParam = LchQueryParam("observationName",
    new StringValueMatcher[ISPObservation] {
      override protected def extractor(obs: ISPObservation): Option[String] =
        obs.toSPObs.map(_.getTitle)
    }
  )

  private[servlet] val ObservationStatusParam = LchQueryParam("observationStatus",
    new BooleanValueMatcher[ISPObservation,ObservationStatus] {
      override protected def extractor(obs: ISPObservation): Option[ObservationStatus] =
        Option(obs).map(ObservationStatus.computeFor)
    }
  )

  private[servlet] val ObservationInstrumentParam = LchQueryParam("observationInstrument",
    new StringValueMatcher[ISPObservation] {
      override protected def extractor(obs: ISPObservation): Option[String] =
        obs.instrumentType
    }
  )

  private[servlet] val ObservationAOParam = LchQueryParam("observationAo",
    new BooleanValueMatcher[ISPObservation, AOConstants.AO] {
      override protected def extractor(obs: ISPObservation): Option[AOConstants.AO] =
        obs.ao.some
    }
  )

  private[servlet] val ObservationClassParam = LchQueryParam("observationClass",
    new StringValueMatcher[ISPObservation] {
      override protected def extractor(obs: ISPObservation): Option[String] =
        Option(obs).map(o => ObsClassService.lookupObsClass(o).displayValue)
    }
  )

  val ProgramParams = List(
    ProgramSemesterParam,
    ProgramTitleParam,
    ProgramReferenceParam,
    ProgramActiveParam,
    ProgramCompletedParam,
    ProgramNotifyPIParam,
    ProgramRolloverParam
  )

  val ObservationParams = List(
    ObservationTOOStatusParam,
    ObservationNameParam,
    ObservationStatusParam,
    ObservationInstrumentParam,
    ObservationAOParam,
    ObservationClassParam
  )

  private lazy val ParamNames = (ProgramParams.map(_.name) ++ ObservationParams.map(_.name)).toSet
  def validParamName(paramName: String): Boolean = ParamNames.contains(paramName)
}

