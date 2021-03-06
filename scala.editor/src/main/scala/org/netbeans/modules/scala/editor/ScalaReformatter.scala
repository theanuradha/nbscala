package org.netbeans.modules.scala.editor

import java.io.StringReader
import javax.swing.text.BadLocationException
import javax.swing.text.StyledDocument
import org.netbeans.modules.editor.indent.spi.Context
import org.netbeans.modules.editor.indent.spi.ExtraLock
import org.netbeans.modules.editor.indent.spi.ReformatTask
import org.netbeans.modules.parsing.api.Source
import org.netbeans.modules.parsing.impl.Utilities
import org.netbeans.modules.scala.core.ScalaMimeResolver
import org.netbeans.modules.scala.editor.options.CodeStyle
import scalariform.formatter.preferences.AlignParameters
import scalariform.formatter.preferences.AlignSingleLineCaseStatements
import scalariform.formatter.preferences.FormattingPreferences
import scalariform.formatter.preferences.IndentSpaces
import scalariform.formatter.preferences.RewriteArrowSymbols
import scalariform.parser.ScalaParserException

class ScalaReformatter(source: Source, context: Context) extends ReformatTask {
  private val doc = context.document.asInstanceOf[StyledDocument]

  val diffOptions = HuntDiff.Options(
    ignoreCase = false,
    ignoreInnerWhitespace = false,
    ignoreLeadingAndtrailingWhitespace = false)

  @throws(classOf[BadLocationException])
  def reformat() {
    val cs = CodeStyle.get(doc)
    val preferences = FormattingPreferences()
      .setPreference(IndentSpaces, cs.indentSize)
      .setPreference(RewriteArrowSymbols, false)
      .setPreference(AlignParameters, true)
      .setPreference(AlignSingleLineCaseStatements, true)

    val indentRegions = context.indentRegions
    java.util.Collections.reverse(indentRegions)
    val regions = indentRegions.iterator

    while (regions.hasNext) {
      val region = regions.next
      val start = region.getStartOffset
      val end = region.getEndOffset
      val length = end - start
      if (start >= 0 && length > 0) {
        val text = doc.getText(start, length)
        val formattedText = try {
          scalariform.formatter.ScalaFormatter.format(text, preferences)
        } catch {
          case ex: ScalaParserException => null
        }

        if (formattedText != null && formattedText.length > 0) {
          val root = doc.getDefaultRootElement

          val diffs = HuntDiff.diff(new StringReader(text), new StringReader(formattedText), diffOptions)
          // reverse the order so we can modify text forward from the end
          java.util.Arrays.sort(diffs, new java.util.Comparator[Diff]() {
            def compare(o1: Diff, o2: Diff) = -o1.firstStart.compareTo(o2.firstStart)
          })

          for (diff <- diffs) {
            diff.tpe match {
              case Diff.ADD =>
                val startLineNo = diff.secondStart
                val startOffset = root.getElement(startLineNo - 1).getStartOffset
                val t = diff.secondText
                doc.insertString(startOffset, t, null)

              case Diff.DELETE =>
                val startLineNo = diff.firstStart
                val endLineNo = diff.firstEnd
                val startOffset = root.getElement(startLineNo - 1).getStartOffset
                val endOffset = root.getElement(endLineNo - 1).getEndOffset
                doc.remove(startOffset, endOffset - startOffset)

              case Diff.CHANGE =>
                val startLineNo = diff.firstStart
                val endLineNo = diff.firstEnd
                val startOffset = root.getElement(startLineNo - 1).getStartOffset
                val endOffset = root.getElement(endLineNo - 1).getEndOffset
                doc.remove(startOffset, endOffset - startOffset)
                val t = diff.secondText
                doc.insertString(startOffset, t, null)
            }
          }
        } else {
          // Cannot be parsed by scalariform, fall back to ScalaFormatter
          new ScalaFormatter(cs, -1).reindent(context)
        }
      }
    }
  }

  def reformatLock: ExtraLock = {
    source.getMimeType match {
      case ScalaMimeResolver.MIME_TYPE => new ExtraLock() {
        def lock() {
          Utilities.acquireParserLock
        }

        def unlock() {
          Utilities.releaseParserLock
        }
      }
      case _ => null
    }
  }
}

object ScalaReformatter {
  /**
   * Reformat task factory produces reformat tasks for the given context.
   * <br/>
   * It should be registered in MimeLookup via xml layer in "/Editors/&lt;mime-type&gt;"
   * folder.
   */
  class Factory extends ReformatTask.Factory {

    /**
     * Create reformatting task.
     *
     * @param context non-null indentation context.
     * @return reformatting task or null if the factory cannot handle the given context.
     */
    def createTask(context: Context): ReformatTask = {
      val source = Source.create(context.document)
      if (source != null) new ScalaReformatter(source, context) else null
    }
  }
}
