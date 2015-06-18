package ml.wolfe.nlp.converters

import java.util.Properties

import edu.arizona.sista.processors.{Document => SistaDocument, Sentence => SistaSentence}
import edu.arizona.sista.processors.corenlp.{CoreNLPProcessor, CoreNLPDocument}
import edu.arizona.sista.processors.fastnlp.FastNLPProcessor
import ml.wolfe.nlp.{Document => WolfeDocument, Sentence => WolfeSentence, Token => WolfeToken}
import ml.wolfe.nlp.ie.CorefAnnotation
import scala.collection.JavaConversions._


/**
 * Convenience methods for processing NLP documents.
 *
 * @author Sebastian Riedel
 * @author Jason Naradowsky
 */
object SISTAProcessors {

  // The main SISTA wrapper for most CoreNLP processes
  lazy val sistaCoreNLPProcessor = new CoreNLPProcessor(basicDependencies = false)
  // A separate processor for calls to MaltParser wrapper returning basic dependencies
  lazy val maltSistaCoreNLPProcessor = new FastNLPProcessor(useMalt = true)
  // A separate processor for calls to Stanford Neural Parser wrapper returning basic dependencies
  lazy val nnSistaCoreNLPProcessor = new FastNLPProcessor(useMalt = false, useBasicDependencies = true)
  // Another processor for basic dependencies extracted from the Stanford constituent parser
  lazy val basicSistaCoreNLPProcessor = new CoreNLPProcessor(basicDependencies = true)

  /**
   * Applies tokenization and sentence splitting to the text.
   * @param text text to process.
   * @return a document containing sentences with basic tokens.
   */
  def mkDocument(text: String): WolfeDocument = {
    println("making document...")
    val result = sistaCoreNLPProcessor.mkDocument(text)
    val sentences = result.sentences map SISTAConverter.toWolfeSentence
    WolfeDocument(text, sentences)
  }

  /**
   * Applies tokenization, sentence splitting, and parsing to the text.
   * @param text text to process.
   * @return a document containing sentences with basic tokens and parse structure.
   */
  def mkParsedDocument(text: String): WolfeDocument = {
    val result = sistaCoreNLPProcessor.mkDocument(text)
    sistaCoreNLPProcessor.parse(result)
    val sentences = result.sentences map SISTAConverter.toFullWolfeSentence
    WolfeDocument(text, sentences)
  }

  /**
   * Calls the full SISTA CoreNLP pipeline and returns a wolfe document.
   * @param text the text to process.
   * @return a document with full annotation.
   */
  def annotate(text: String): WolfeDocument = {
    val result = sistaCoreNLPProcessor.annotate(text)
    val sentences = result.sentences map SISTAConverter.toWolfeSentence
    val coref = SISTAConverter.toWolfeCoreference(result.coreferenceChains.get).toArray
    WolfeDocument(text, sentences, coref = CorefAnnotation(coref))
  }

  /**
   * Calls the SISTA CoreNLP components as specified by the arguments
   * @param text the text to process
   * @param posTagger part-of-speech tagger
   * @param lemmatizer lemmatizer
   * @param parser constituent and dependency parses
   * @param ner named entity recognition
   * @param coreference coreference resolution
   * @param srl (NOT SUPPORTED BY CoreNLP) semantic role labeling
   * @return fully annotated document
   */

  def annotate(text: String,
               posTagger: Boolean=false,
               lemmatizer: Boolean=false,
               parser: Boolean=false,
               ner: Boolean=false,
               coreference: Boolean=false,
               srl: Boolean = false,
               prereqs: Boolean = false): WolfeDocument = {
    println("again making doc...")
    val result = sistaCoreNLPProcessor.mkDocument(text)
    if (posTagger || (prereqs && (coreference || parser || ner))) sistaCoreNLPProcessor.tagPartsOfSpeech(result)
    if (parser || (prereqs && coreference)) sistaCoreNLPProcessor.parse(result)
    if (lemmatizer || (prereqs && (coreference || ner))) sistaCoreNLPProcessor.lemmatize(result)
    if (ner || (prereqs && coreference)) sistaCoreNLPProcessor.recognizeNamedEntities(result)
    // NO SRL SUPPORT IN CoreNLP
    // if (srl) sistaCoreNLPProcessor.labelSemanticRoles(result)
    if (coreference && !prereqs) {
      require(posTagger && lemmatizer && ner && parser, "Coreference resolution requires execution of POS tagger, lemmatizer, NER and parser")
      sistaCoreNLPProcessor.resolveCoreference(result)
    }
    sistaToWolfeDocument(result, text = text)
  }

  def sistaToWolfeDocument(doc: SistaDocument, text: String = ""): WolfeDocument = {
    val sentences = doc.sentences map SISTAConverter.toFullWolfeSentence
    val corefSeq = doc.coreferenceChains.map(c => SISTAConverter.toWolfeCoreference(c).toArray)
    WolfeDocument(text, sentences, coref = corefSeq.map(CorefAnnotation(_)).getOrElse(CorefAnnotation.empty))
  }

  def parse(words: Array[String], mode: String = "NN"): WolfeDocument = {
    val doc = sistaCoreNLPProcessor.mkDocument(words.mkString(" "))
    sistaCoreNLPProcessor.tagPartsOfSpeech(doc)
    sistaCoreNLPProcessor.lemmatize(doc)
    if (mode == "BASIC") {
      basicSistaCoreNLPProcessor.parse(doc)
    }
    else if (mode == "MALT") {
      maltSistaCoreNLPProcessor.parse(doc)
    }
    else if (mode == "NN") {
      nnSistaCoreNLPProcessor.parse(doc)
    }
    else {
      // Default to the standard Stanford parser
      sistaCoreNLPProcessor.parse(doc)
    }
    sistaToWolfeDocument(doc, text = words.mkString(" "))
  }

  def main(args: Array[String]): Unit = {
    val sent = "the quick brown fox jumped over the lazy dog ."
    val tokens = sent.split(" ").map(w => WolfeToken(word = w))
    parse(tokens.map(_.word))
    annotate(sent,  ner = true, parser = true, prereqs = true)
  }
}