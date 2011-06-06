package de.lmu.ifi.dbs.elki.result.textwriter;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import de.lmu.ifi.dbs.elki.data.Cluster;
import de.lmu.ifi.dbs.elki.data.Clustering;
import de.lmu.ifi.dbs.elki.data.FeatureVector;
import de.lmu.ifi.dbs.elki.data.HierarchicalClassLabel;
import de.lmu.ifi.dbs.elki.data.LabelList;
import de.lmu.ifi.dbs.elki.data.SimpleClassLabel;
import de.lmu.ifi.dbs.elki.data.model.Model;
import de.lmu.ifi.dbs.elki.data.type.TypeUtil;
import de.lmu.ifi.dbs.elki.database.Database;
import de.lmu.ifi.dbs.elki.database.ids.DBID;
import de.lmu.ifi.dbs.elki.database.ids.DBIDs;
import de.lmu.ifi.dbs.elki.datasource.bundle.SingleObjectBundle;
import de.lmu.ifi.dbs.elki.distance.distancevalue.Distance;
import de.lmu.ifi.dbs.elki.logging.Logging;
import de.lmu.ifi.dbs.elki.math.linearalgebra.Vector;
import de.lmu.ifi.dbs.elki.result.AnnotationResult;
import de.lmu.ifi.dbs.elki.result.CollectionResult;
import de.lmu.ifi.dbs.elki.result.HierarchicalResult;
import de.lmu.ifi.dbs.elki.result.IterableResult;
import de.lmu.ifi.dbs.elki.result.OrderingResult;
import de.lmu.ifi.dbs.elki.result.Result;
import de.lmu.ifi.dbs.elki.result.ResultUtil;
import de.lmu.ifi.dbs.elki.result.SettingsResult;
import de.lmu.ifi.dbs.elki.result.textwriter.naming.NamingScheme;
import de.lmu.ifi.dbs.elki.result.textwriter.naming.SimpleEnumeratingScheme;
import de.lmu.ifi.dbs.elki.result.textwriter.writers.TextWriterDoubleDoublePair;
import de.lmu.ifi.dbs.elki.result.textwriter.writers.TextWriterObjectArray;
import de.lmu.ifi.dbs.elki.result.textwriter.writers.TextWriterObjectComment;
import de.lmu.ifi.dbs.elki.result.textwriter.writers.TextWriterObjectInline;
import de.lmu.ifi.dbs.elki.result.textwriter.writers.TextWriterPair;
import de.lmu.ifi.dbs.elki.result.textwriter.writers.TextWriterTextWriteable;
import de.lmu.ifi.dbs.elki.result.textwriter.writers.TextWriterTriple;
import de.lmu.ifi.dbs.elki.result.textwriter.writers.TextWriterVector;
import de.lmu.ifi.dbs.elki.utilities.HandlerList;
import de.lmu.ifi.dbs.elki.utilities.exceptions.UnableToComplyException;
import de.lmu.ifi.dbs.elki.utilities.optionhandling.parameterization.SerializedParameterization;
import de.lmu.ifi.dbs.elki.utilities.optionhandling.parameters.ClassParameter;
import de.lmu.ifi.dbs.elki.utilities.optionhandling.parameters.Parameter;
import de.lmu.ifi.dbs.elki.utilities.pairs.DoubleDoublePair;
import de.lmu.ifi.dbs.elki.utilities.pairs.Pair;
import de.lmu.ifi.dbs.elki.utilities.pairs.Triple;

/**
 * Class to write a result to human-readable text output
 * 
 * @author Erich Schubert
 * 
 * @apiviz.landmark
 * @apiviz.uses TextWriterStream oneway - - writesTo
 * @apiviz.composedOf TextWriterWriterInterface
 * @apiviz.has NamingScheme
 */
public class TextWriter {
  /**
   * Logger
   */
  private static final Logging logger = Logging.getLogger(TextWriter.class);

  /**
   * Extension for txt-files.
   */
  public static final String FILE_EXTENSION = ".txt";

  /**
   * Hash map for supported classes in writer.
   */
  public final static HandlerList<TextWriterWriterInterface<?>> writers = new HandlerList<TextWriterWriterInterface<?>>();

  /**
   * Add some default handlers
   */
  static {
    TextWriterObjectInline trivialwriter = new TextWriterObjectInline();
    writers.insertHandler(Object.class, new TextWriterObjectComment());
    writers.insertHandler(Pair.class, new TextWriterPair());
    writers.insertHandler(DoubleDoublePair.class, new TextWriterDoubleDoublePair());
    writers.insertHandler(Triple.class, new TextWriterTriple());
    writers.insertHandler(FeatureVector.class, trivialwriter);
    // these object can be serialized inline with toString()
    writers.insertHandler(String.class, trivialwriter);
    writers.insertHandler(Double.class, trivialwriter);
    writers.insertHandler(Integer.class, trivialwriter);
    writers.insertHandler(String[].class, new TextWriterObjectArray<String>());
    writers.insertHandler(Double[].class, new TextWriterObjectArray<Double>());
    writers.insertHandler(Integer[].class, new TextWriterObjectArray<Integer>());
    writers.insertHandler(BitSet.class, trivialwriter);
    writers.insertHandler(Vector.class, new TextWriterVector());
    writers.insertHandler(Distance.class, trivialwriter);
    writers.insertHandler(SimpleClassLabel.class, trivialwriter);
    writers.insertHandler(HierarchicalClassLabel.class, trivialwriter);
    writers.insertHandler(LabelList.class, trivialwriter);
    writers.insertHandler(DBID.class, trivialwriter);
    // Objects that have an own writeToText method.
    writers.insertHandler(TextWriteable.class, new TextWriterTextWriteable());
  }

  /**
   * For producing unique filenames.
   */
  protected Map<String, Object> filenames = new HashMap<String, Object>();

  /**
   * Try to find a unique file name.
   * 
   * @param result Result we print
   * @param filenamepre File name prefix to use
   * @return
   */
  protected String getFilename(Object result, String filenamepre) {
    if(filenamepre == null || filenamepre.length() == 0) {
      filenamepre = "result";
    }
    int i = 0;
    while(true) {
      String filename;
      if(i > 0) {
        filename = filenamepre + "-" + i;
      }
      else {
        filename = filenamepre;
      }
      Object existing = filenames.get(filename);
      if(existing == null || existing == result) {
        filenames.put(filename, result);
        return filename;
      }
      i++;
    }
  }

  /**
   * Writes a header providing information concerning the underlying database
   * and the specified parameter-settings.
   * 
   * @param out the print stream where to write
   * @param sr the settings to be written into the header
   */
  protected void printSettings(TextWriterStream out, List<SettingsResult> sr) {
    out.commentPrintSeparator();
    out.commentPrintLn("Settings:");

    if(sr != null) {
      for(SettingsResult settings : sr) {
        Object last = null;
        for(Pair<Object, Parameter<?, ?>> setting : settings.getSettings()) {
          if(setting.first != last && setting.first != null) {
            if(last != null) {
              out.commentPrintLn("");
            }
            String name;
            try {
              if(setting.first instanceof Class) {
                name = ((Class<?>) setting.first).getName();
              }
              else {
                name = setting.first.getClass().getName();
              }
              if(ClassParameter.class.isInstance(setting.first)) {
                name = ((ClassParameter<?>) setting.first).getValue().getName();
              }
            }
            catch(NullPointerException e) {
              name = "[null]";
            }
            out.commentPrintLn(name);
            last = setting.first;
          }
          String name = setting.second.getOptionID().getName();
          String value = "[unset]";
          try {
            if(setting.second.isDefined()) {
              value = setting.second.getValueAsString();
            }
          }
          catch(NullPointerException e) {
            value = "[null]";
          }
          out.commentPrintLn(SerializedParameterization.OPTION_PREFIX + name + " " + value);
        }
      }
    }

    out.commentPrintSeparator();
    out.flush();
  }

  /**
   * Stream output.
   * 
   * @param db Database object
   * @param r Result class
   * @param streamOpener output stream manager
   * @throws UnableToComplyException when no usable results were found
   * @throws IOException on IO error
   */
  public void output(Database db, Result r, StreamFactory streamOpener) throws UnableToComplyException, IOException {
    List<AnnotationResult<?>> ra = null;
    List<OrderingResult> ro = null;
    List<Clustering<? extends Model>> rc = null;
    List<IterableResult<?>> ri = null;
    List<SettingsResult> rs = null;
    HashSet<Result> otherres = null;

    ra = ResultUtil.getAnnotationResults(r);
    ro = ResultUtil.getOrderingResults(r);
    rc = ResultUtil.getClusteringResults(r);
    ri = ResultUtil.getIterableResults(r);
    rs = ResultUtil.getSettingsResults(r);
    // collect other results
    if(r instanceof HierarchicalResult) {
      final List<Result> resultList = ResultUtil.filterResults((HierarchicalResult) r, Result.class);
      otherres = new HashSet<Result>(resultList);
      otherres.removeAll(ra);
      otherres.removeAll(ro);
      otherres.removeAll(rc);
      otherres.removeAll(ri);
      otherres.removeAll(rs);
      otherres.remove(db);
      Iterator<Result> it = otherres.iterator();
      while(it.hasNext()) {
        if(it.next() instanceof HierarchicalResult) {
          it.remove();
        }
      }
    }

    if(ra == null && ro == null && rc == null && ri == null) {
      throw new UnableToComplyException("No printable result found.");
    }

    if(ri != null && ri.size() > 0) {
      // TODO: associations are not passed to ri results.
      for(IterableResult<?> rii : ri) {
        writeIterableResult(db, streamOpener, rii, rs);
      }
    }
    if(rc != null && rc.size() > 0) {
      for(Clustering<?> c : rc) {
        NamingScheme naming = new SimpleEnumeratingScheme(c);
        for(Cluster<?> clus : c.getAllClusters()) {
          writeClusterResult(db, streamOpener, clus, ra, naming, rs);
        }
      }
    }
    if(ro != null && ro.size() > 0) {
      for(OrderingResult ror : ro) {
        writeOrderingResult(db, streamOpener, ror, ra, rs);
      }
    }
    if(otherres != null && otherres.size() > 0) {
      for(Result otherr : otherres) {
        writeOtherResult(db, streamOpener, otherr, rs);
      }
    }
  }

  private void printObject(TextWriterStream out, Database db, final DBID objID, List<AnnotationResult<?>> ra) throws UnableToComplyException, IOException {
    SingleObjectBundle bundle = db.getBundle(objID);
    // Write database element itself.
    for(int i = 0; i < bundle.metaLength(); i++) {
      Object obj = bundle.data(i);
      TextWriterWriterInterface<?> owriter = out.getWriterFor(obj);
      if(owriter == null) {
        throw new UnableToComplyException("No handler for database object itself: " + obj.getClass().getSimpleName());
      }
      String lbl = null;
      // TODO: ugly compatibility hack...
      if(TypeUtil.DBID.isAssignableFromType(bundle.meta(i))) {
        lbl = "ID";
      }
      owriter.writeObject(out, lbl, obj);
    }

    // print the annotations
    if(ra != null) {
      for(AnnotationResult<?> a : ra) {
        String label = a.getAssociationID().getLabel();
        Object value = a.getValueFor(objID);
        if(value == null) {
          continue;
        }
        TextWriterWriterInterface<?> writer = out.getWriterFor(value);
        if(writer == null) {
          // Ignore
          continue;
        }
        writer.writeObject(out, label, value);
      }
    }
    out.flush();
    out.flush();
  }

  private void writeOtherResult(Database db, StreamFactory streamOpener, Result r, List<SettingsResult> rs) throws UnableToComplyException, IOException {
    PrintStream outStream = streamOpener.openStream(getFilename(r, r.getShortName()));
    TextWriterStream out = new TextWriterStream(outStream, writers);
    TextWriterWriterInterface<?> owriter = out.getWriterFor(r);
    if(owriter == null) {
      throw new UnableToComplyException("No handler for result class: " + r.getClass().getSimpleName());
    }
    // Write settings preamble
    printSettings(out, rs);
    // Write data
    owriter.writeObject(out, null, r);
    out.flush();
  }

  private void writeClusterResult(Database db, StreamFactory streamOpener, Cluster<?> clus, List<AnnotationResult<?>> ra, NamingScheme naming, List<SettingsResult> sr) throws FileNotFoundException, UnableToComplyException, IOException {
    String filename = null;
    if(naming != null) {
      filename = filenameFromLabel(naming.getNameFor(clus));
    }
    else {
      filename = "cluster";
    }

    PrintStream outStream = streamOpener.openStream(getFilename(clus, filename));
    TextWriterStream out = new TextWriterStream(outStream, writers);
    printSettings(out, sr);

    // Write cluster information
    out.commentPrintLn("Cluster: " + naming.getNameFor(clus));
    if(clus.getParents().size() > 0) {
      StringBuffer buf = new StringBuffer();
      buf.append("Parents:");
      for(Cluster<?> parent : clus.getParents()) {
        buf.append(" ").append(naming.getNameFor(parent));
      }
      out.commentPrintLn(buf.toString());
    }
    if(clus.getChildren().size() > 0) {
      StringBuffer buf = new StringBuffer();
      buf.append("Children:");
      for(Cluster<?> child : clus.getChildren()) {
        buf.append(" ").append(naming.getNameFor(child));
      }
      out.commentPrintLn(buf.toString());
    }
    out.flush();

    // print ids.
    DBIDs ids = clus.getIDs();
    Iterator<DBID> iter = ids.iterator();

    while(iter.hasNext()) {
      printObject(out, db, iter.next(), ra);
    }
    out.commentPrintSeparator();
    out.flush();
  }

  private void writeIterableResult(Database db, StreamFactory streamOpener, IterableResult<?> ri, List<SettingsResult> sr) throws UnableToComplyException, IOException {
    PrintStream outStream = streamOpener.openStream(getFilename(ri, ri.getShortName()));
    TextWriterStream out = new TextWriterStream(outStream, writers);
    printSettings(out, sr);

    // hack to print collectionResult header information
    if(ri instanceof CollectionResult<?>) {
      final Collection<String> hdr = ((CollectionResult<?>) ri).getHeader();
      if(hdr != null) {
        for(String header : hdr) {
          out.commentPrintLn(header);
        }
        out.flush();
      }
    }
    Iterator<?> i = ri.iterator();
    while(i.hasNext()) {
      Object o = i.next();
      TextWriterWriterInterface<?> writer = out.getWriterFor(o);
      if(writer != null) {
        writer.writeObject(out, null, o);
      }
      out.flush();
    }
    out.commentPrintSeparator();
    out.flush();
  }

  private void writeOrderingResult(Database db, StreamFactory streamOpener, OrderingResult or, List<AnnotationResult<?>> ra, List<SettingsResult> sr) throws IOException, UnableToComplyException {
    PrintStream outStream = streamOpener.openStream(getFilename(or, or.getShortName()));
    TextWriterStream out = new TextWriterStream(outStream, writers);
    printSettings(out, sr);

    Iterator<DBID> i = or.iter(db.getDBIDs());
    while(i.hasNext()) {
      printObject(out, db, i.next(), ra);
    }
    out.commentPrintSeparator();
    out.flush();
  }

  /**
   * Derive a file name from the cluster label.
   * 
   * @param label cluster label
   * @return cleaned label suitable for file names.
   */
  private String filenameFromLabel(String label) {
    return label.toLowerCase().replaceAll("[^a-zA-Z0-9_.\\[\\]-]", "_");
  }
}
