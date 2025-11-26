package infrastructure.actors

import org.apache.pekko.http.scaladsl.marshalling.{Marshaller, ToEntityMarshaller}
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity}
import org.apache.pekko.http.scaladsl.unmarshalling.{FromEntityUnmarshaller, Unmarshaller}
import io.circe.parser.decode
import io.circe.{Decoder, Encoder}
import io.circe.syntax._

trait CirceSupport {
  implicit val stringUnmarshaller: FromEntityUnmarshaller[String] =
    Unmarshaller.stringUnmarshaller.forContentTypes(ContentTypes.`application/json`)

  implicit val stringMarshaller: ToEntityMarshaller[String] =
    Marshaller.withFixedContentType(ContentTypes.`application/json`) { value =>
      HttpEntity(ContentTypes.`application/json`, value)
    }

  implicit def circeUnmarshaller[A: Decoder]: FromEntityUnmarshaller[A] =
    Unmarshaller.stringUnmarshaller.forContentTypes(ContentTypes.`application/json`).map { data =>
      decode[A](data).fold(throw _, identity)
    }

  implicit def circeMarshaller[A: Encoder]: ToEntityMarshaller[A] =
    Marshaller.withFixedContentType(ContentTypes.`application/json`) { value =>
      HttpEntity(ContentTypes.`application/json`, value.asJson.noSpaces)
    }
}
