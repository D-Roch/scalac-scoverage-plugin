package reaktor.scct.report

import java.io.File
import io.Source

class SourceLoader {
  def linesFor(sourceFile: String) = {
    val src = Source.fromFile(new File(sourceFile))
    toLines(src)
  }


  def toLines(source: Source) = {
    def toLines(acc: List[String]): List[String] = {
      if (!source.hasNext) {
        acc.reverse
      } else {
        val line = source.takeWhile(_ != '\n').mkString
        toLines(line+"\n" :: acc)
      }
    }
    toLines(List())
  }
}

