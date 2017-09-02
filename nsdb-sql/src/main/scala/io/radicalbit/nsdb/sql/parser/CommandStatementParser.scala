package io.radicalbit.nsdb.sql.parser

import io.radicalbit.nsdb.common.statement._

import scala.util.parsing.combinator.{PackratParsers, RegexParsers}
import scala.util.{Try, Failure => ScalaFailure, Success => ScalaSuccess}

class CommandStatementParser extends RegexParsers with PackratParsers {

  implicit class InsensitiveString(str: String) {
    def ignoreCase: Parser[String] = ("""(?i)\Q""" + str + """\E""").r ^^ { _.toString.toUpperCase }
  }

  private val Describe   = "DESCRIBE" ignoreCase
  private val Metrics    = "METRICS" ignoreCase
  private val Namespaces = "NAMESPACES" ignoreCase
  private val Show       = "SHOW" ignoreCase
  private val Use        = "USE" ignoreCase

  private val namespace = """(^[a-zA-Z][a-zA-Z0-9_]*)""".r
  private val metric    = """(^[a-zA-Z][a-zA-Z0-9_]*)""".r

  private def showNamespaces = Show ~ Namespaces ^^ {
    case _ => ShowNamespaces
  }

  private def useNamespace = Use ~> namespace ^^ {
    case ns => UseNamespace(ns)
  }

  private def showMetrics(namespace: Option[String]) = Show ~ Metrics ^^ {
    case _ if (namespace.isDefined) => ShowMetrics(namespace.get)
    case _                          => sys.error("Please select a valid namespace to list the associated metrics.")
  }

  private def describeMetric(namespace: Option[String]) = Describe ~> metric ^^ {
    case m if (namespace.isDefined) => DescribeMetric(namespace = namespace.get, metric = m)
    case _                          => sys.error("Please select a valid namespace to describe the given metric.")
  }

  private def commands(namespace: Option[String]) =
    showNamespaces | useNamespace | showMetrics(namespace) | describeMetric(namespace)

  def parse(namespace: Option[String], input: String): Try[CommandStatement] =
    Try(parse(commands(namespace), s"$input;")).flatMap {
      case Success(res, _) => ScalaSuccess(res.asInstanceOf[CommandStatement])
      case Error(msg, _)   => ScalaFailure(new RuntimeException(msg))
      case Failure(msg, _) => ScalaFailure(new RuntimeException(msg))
    }
}
