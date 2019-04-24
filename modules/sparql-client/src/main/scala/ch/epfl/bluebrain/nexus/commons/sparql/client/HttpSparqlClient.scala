package ch.epfl.bluebrain.nexus.commons.sparql.client

import akka.http.scaladsl.client.RequestBuilding.Post
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{Accept, HttpCredentials}
import cats.MonadError
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import ch.epfl.bluebrain.nexus.commons.http.HttpClient.UntypedHttpClient
import ch.epfl.bluebrain.nexus.commons.http.{HttpClient, RdfMediaTypes, UnexpectedUnsuccessfulHttpResponse}
import journal.Logger
import org.apache.jena.query.ParameterizedSparqlString

import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

/**
  * A minimalistic sparql client that operates on a predefined endpoint with optional HTTP basic authentication.
  *
  * @param endpoint    the sparql endpoint
  * @param credentials the credentials to use when communicating with the sparql endpoint
  */
class HttpSparqlClient[F[_]](endpoint: Uri, credentials: Option[HttpCredentials])(implicit F: MonadError[F, Throwable],
                                                                                  cl: UntypedHttpClient[F],
                                                                                  rsJson: HttpClient[F, SparqlResults],
                                                                                  ec: ExecutionContext)
    extends SparqlClient[F] {

  private val log = Logger[this.type]

  def query[A](query: String)(implicit rs: HttpClient[F, A]): F[A] = {
    val accept   = Accept(MediaRange.One(RdfMediaTypes.`application/sparql-results+json`, 1F))
    val formData = FormData("query" -> query)
    val req      = Post(endpoint, formData).withHeaders(accept)
    rs(addCredentials(req)).handleErrorWith {
      case UnexpectedUnsuccessfulHttpResponse(resp, body) =>
        error(req, body, resp.status, "sparql query")
      case NonFatal(th) =>
        log.error(s"""Unexpected Sparql response for sparql query:
                     |Request: '${req.method} ${req.uri}'
                     |Query: '$query'
           """.stripMargin)
        F.raiseError(th)
    }
  }

  def bulk(queries: SparqlWriteQuery*): F[Unit] = {
    val queryString = queries.map(_.value).mkString("\n")
    val pss         = new ParameterizedSparqlString
    pss.setCommandText(queryString)
    F.catchNonFatal(pss.asUpdate()).flatMap { _ =>
      val formData = FormData("update" -> queryString)
      val qParams =
        uniqueGraph(queries).map(graph => Query("using-named-graph-uri" -> graph.toString())).getOrElse(Query.Empty)
      val req = Post(endpoint.withQuery(qParams), formData)
      log.debug(s"Executing sparql update: '$queries'")
      cl(addCredentials(req)).flatMap { resp =>
        resp.status match {
          case StatusCodes.OK => cl.discardBytes(resp.entity).map(_ => ())
          case _              => error(req, resp, "sparql update")
        }
      }
    }
  }

  private def uniqueGraph(query: Seq[SparqlWriteQuery]): Option[Uri] =
    query.map(_.graph).distinct match {
      case head :: Nil => Some(head)
      case _           => None
    }

  private[client] def error[A](req: HttpRequest, resp: HttpResponse, op: String): F[A] =
    cl.toString(resp.entity).flatMap { body =>
      error(req, body, resp.status, op)
    }

  private def error[A](req: HttpRequest, body: String, status: StatusCode, op: String): F[A] = {
    log.error(s"""Unexpected Blazegraph response for '$op':
                   |Request: '${req.method} ${req.uri}'
                   |Status: '$status'
                   |Response: '$body'
           """.stripMargin)
    F.raiseError(SparqlFailure.fromStatusCode(status, body))
  }

  protected def addCredentials(req: HttpRequest): HttpRequest = credentials match {
    case None        => req
    case Some(value) => req.addCredentials(value)
  }
}

object HttpSparqlClient {

  def apply[F[_]](endpoint: Uri, credentials: Option[HttpCredentials])(implicit F: MonadError[F, Throwable],
                                                                       cl: UntypedHttpClient[F],
                                                                       rsJson: HttpClient[F, SparqlResults],
                                                                       ec: ExecutionContext): SparqlClient[F] =
    new HttpSparqlClient[F](endpoint, credentials)

}
