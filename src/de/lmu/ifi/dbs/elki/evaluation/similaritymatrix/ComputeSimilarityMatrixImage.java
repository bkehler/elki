package de.lmu.ifi.dbs.elki.evaluation.similaritymatrix;

/*
 This file is part of ELKI:
 Environment for Developing KDD-Applications Supported by Index-Structures

 Copyright (C) 2012
 Ludwig-Maximilians-Universität München
 Lehr- und Forschungseinheit für Datenbanksysteme
 ELKI Development Team

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU Affero General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;

import javax.imageio.ImageIO;

import de.lmu.ifi.dbs.elki.algorithm.AbstractAlgorithm;
import de.lmu.ifi.dbs.elki.database.Database;
import de.lmu.ifi.dbs.elki.database.ids.ArrayDBIDs;
import de.lmu.ifi.dbs.elki.database.ids.ArrayModifiableDBIDs;
import de.lmu.ifi.dbs.elki.database.ids.DBID;
import de.lmu.ifi.dbs.elki.database.ids.DBIDUtil;
import de.lmu.ifi.dbs.elki.database.query.distance.DistanceQuery;
import de.lmu.ifi.dbs.elki.database.relation.Relation;
import de.lmu.ifi.dbs.elki.distance.distancefunction.DistanceFunction;
import de.lmu.ifi.dbs.elki.distance.distancefunction.EuclideanDistanceFunction;
import de.lmu.ifi.dbs.elki.distance.distancevalue.NumberDistance;
import de.lmu.ifi.dbs.elki.evaluation.Evaluator;
import de.lmu.ifi.dbs.elki.logging.Logging;
import de.lmu.ifi.dbs.elki.logging.LoggingUtil;
import de.lmu.ifi.dbs.elki.logging.progress.FiniteProgress;
import de.lmu.ifi.dbs.elki.math.DoubleMinMax;
import de.lmu.ifi.dbs.elki.result.HierarchicalResult;
import de.lmu.ifi.dbs.elki.result.IterableResult;
import de.lmu.ifi.dbs.elki.result.OrderingResult;
import de.lmu.ifi.dbs.elki.result.PixmapResult;
import de.lmu.ifi.dbs.elki.result.Result;
import de.lmu.ifi.dbs.elki.result.ResultUtil;
import de.lmu.ifi.dbs.elki.result.outlier.OutlierResult;
import de.lmu.ifi.dbs.elki.utilities.optionhandling.AbstractParameterizer;
import de.lmu.ifi.dbs.elki.utilities.optionhandling.OptionID;
import de.lmu.ifi.dbs.elki.utilities.optionhandling.parameterization.Parameterization;
import de.lmu.ifi.dbs.elki.utilities.optionhandling.parameters.Flag;
import de.lmu.ifi.dbs.elki.utilities.optionhandling.parameters.ObjectParameter;
import de.lmu.ifi.dbs.elki.utilities.scaling.LinearScaling;
import de.lmu.ifi.dbs.elki.utilities.scaling.ScalingFunction;

/**
 * Compute a similarity matrix for a distance function.
 * 
 * @author Erich Schubert
 * 
 * @apiviz.has SimilarityMatrix oneway - - «create»
 * 
 * @param <O> Object class
 */
public class ComputeSimilarityMatrixImage<O> implements Evaluator {
  /**
   * The logger.
   */
  static final Logging logger = Logging.getLogger(ComputeSimilarityMatrixImage.class);

  /**
   * OptionID for the scaling function to use
   */
  public static final OptionID SCALING_ID = OptionID.getOrCreateOptionID("simmatrix.scaling", "Class to use as scaling function.");

  /**
   * OptionID to skip zero values when plotting to increase contrast.
   */
  public static final OptionID SKIPZERO_ID = OptionID.getOrCreateOptionID("simmatrix.skipzero", "Skip zero values when computing the colors to increase contrast.");

  /**
   * The distance function to use
   */
  private DistanceFunction<? super O, ? extends NumberDistance<?, ?>> distanceFunction;

  /**
   * Scaling function to use
   */
  private ScalingFunction scaling;

  /**
   * Skip zero values.
   */
  private boolean skipzero = false;

  /**
   * Constructor.
   * 
   * @param distanceFunction Distance function to use
   * @param scaling Scaling function to use for contrast
   * @param skipzero Skip zero values when scaling.
   */
  public ComputeSimilarityMatrixImage(DistanceFunction<? super O, ? extends NumberDistance<?, ?>> distanceFunction, ScalingFunction scaling, boolean skipzero) {
    super();
    this.distanceFunction = distanceFunction;
    this.scaling = scaling;
    this.skipzero = skipzero;
  }

  /**
   * Compute the actual similarity image.
   * 
   * @param relation Relation
   * @param iter DBID iterator
   * @return result object
   */
  private SimilarityMatrix computeSimilarityMatrixImage(Relation<O> relation, Iterator<DBID> iter) {
    ArrayModifiableDBIDs order = DBIDUtil.newArray(relation.size());
    while(iter.hasNext()) {
      Object o = iter.next();
      if(!(o instanceof DBID)) {
        throw new IllegalStateException("Iterable result contained non-DBID - result didn't satisfy requirements");
      }
      else {
        order.add((DBID) o);
      }
    }
    if(order.size() != relation.size()) {
      throw new IllegalStateException("Iterable result doesn't match database size - incomplete ordering?");
    }
    DistanceQuery<O, ? extends NumberDistance<?, ?>> dq = distanceFunction.instantiate(relation);
    final int size = order.size();

    // When the logging is in the outer loop, it's just 2*size (providing enough
    // resolution)
    final int ltotal = 2 * size; // size * (size + 1);
    FiniteProgress prog = logger.isVerbose() ? new FiniteProgress("Similarity Matrix Image", ltotal, logger) : null;

    // Note: we assume that we have an efficient distance cache available,
    // since we are using 2*O(n*n) distance computations.
    DoubleMinMax minmax = new DoubleMinMax();
    for(int x = 0; x < size; x++) {
      DBID id1 = order.get(x);
      for(int y = x; y < size; y++) {
        DBID id2 = order.get(y);
        final double dist = dq.distance(id1, id2).doubleValue();
        if(!Double.isNaN(dist) && !Double.isInfinite(dist) /* && dist > 0.0 */) {
          if(!skipzero || dist != 0.0) {
            minmax.put(dist);
          }
        }
      }
      if(prog != null) {
        prog.incrementProcessed(logger);
      }
    }

    double zoom = minmax.getMax() - minmax.getMin();
    if(zoom > 0.0) {
      zoom = 1. / zoom;
    }
    LinearScaling scale = new LinearScaling(zoom, -minmax.getMin() * zoom);
    BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
    for(int x = 0; x < size; x++) {
      DBID id1 = order.get(x);
      for(int y = x; y < size; y++) {
        DBID id2 = order.get(y);
        double ddist = dq.distance(id1, id2).doubleValue();
        if(ddist > 0.0) {
          ddist = scale.getScaled(ddist);
        }
        // Apply extra scaling
        if(scaling != null) {
          ddist = scaling.getScaled(ddist);
        }
        int dist = 0xFF & (int) (255 * ddist);
        int col = 0xff000000 | (dist << 16) | (dist << 8) | dist;
        img.setRGB(x, y, col);
        img.setRGB(y, x, col);
      }
      if(prog != null) {
        prog.incrementProcessed(logger);
      }
    }
    if(prog != null) {
      prog.ensureCompleted(logger);
    }

    return new SimilarityMatrix(img, relation, order);
  }

  /**
   * Wrap the uncheckable cast with the manual check.
   * 
   * @param ir Interable result
   * @return Iterator if Integer iterable, null otherwise.
   */
  @SuppressWarnings("unchecked")
  private Iterator<DBID> getDBIDIterator(IterableResult<?> ir) {
    Iterator<?> testit = ir.iterator();
    if(testit.hasNext() && (testit.next() instanceof DBID)) {
      // note: we DO want a fresh iterator here!
      return (Iterator<DBID>) ir.iterator();
    }
    return null;
  }

  @Override
  public void processNewResult(HierarchicalResult baseResult, Result result) {
    Database db = ResultUtil.findDatabase(baseResult);
    boolean nonefound = true;
    List<OutlierResult> oresults = ResultUtil.getOutlierResults(result);
    List<IterableResult<?>> iterables = ResultUtil.getIterableResults(result);
    List<OrderingResult> orderings = ResultUtil.getOrderingResults(result);
    // Outlier results are the main use case.
    for(OutlierResult o : oresults) {
      final OrderingResult or = o.getOrdering();
      Relation<O> relation = db.getRelation(distanceFunction.getInputTypeRestriction());
      db.getHierarchy().add(or, computeSimilarityMatrixImage(relation, or.iter(relation.getDBIDs())));
      // Process them only once.
      orderings.remove(or);
      nonefound = false;
    }

    // try iterable results first
    // FIXME: find the appropriate place to call addDerivedResult
    for(IterableResult<?> ir : iterables) {
      Iterator<DBID> iter = getDBIDIterator(ir);
      if(iter != null) {
        Relation<O> relation = db.getRelation(distanceFunction.getInputTypeRestriction());
        db.getHierarchy().add(ir, computeSimilarityMatrixImage(relation, iter));
        nonefound = false;
      }
    }
    // FIXME: find appropriate place to add the derived result
    // otherwise apply an ordering to the database IDs.
    for(OrderingResult or : orderings) {
      Relation<O> relation = db.getRelation(distanceFunction.getInputTypeRestriction());
      Iterator<DBID> iter = or.iter(relation.getDBIDs());
      db.getHierarchy().add(or, computeSimilarityMatrixImage(relation, iter));
      nonefound = false;
    }

    if(nonefound) {
      // Use the database ordering.
      // But be careful to NOT cause a loop, process new databases only.
      List<Database> iter = ResultUtil.filterResults(result, Database.class);
      for(Database database : iter) {
        // Get an arbitrary representation
        Relation<O> relation = database.getRelation(distanceFunction.getInputTypeRestriction());
        db.getHierarchy().add(db, computeSimilarityMatrixImage(relation, relation.iterDBIDs()));
      }
    }
  }

  /**
   * Similarity matrix image.
   * 
   * @author Erich Schubert
   */
  public static class SimilarityMatrix implements PixmapResult {
    /**
     * Prefix for filenames
     */
    private static final String IMGFILEPREFIX = "elki-pixmap-";

    /**
     * The database
     */
    Relation<?> relation;

    /**
     * The database IDs used
     */
    ArrayDBIDs ids;

    /**
     * Our image
     */
    RenderedImage img;

    /**
     * The file we have written the image to
     */
    File imgfile = null;

    /**
     * Constructor
     * 
     * @param img Image data
     */
    public SimilarityMatrix(RenderedImage img, Relation<?> relation, ArrayDBIDs ids) {
      super();
      this.img = img;
      this.relation = relation;
      this.ids = ids;
    }

    @Override
    public RenderedImage getImage() {
      return img;
    }

    @Override
    public File getAsFile() {
      if(imgfile == null) {
        try {
          imgfile = File.createTempFile(IMGFILEPREFIX, ".png");
          imgfile.deleteOnExit();
          ImageIO.write(img, "PNG", imgfile);
        }
        catch(IOException e) {
          LoggingUtil.exception("Could not generate OPTICS plot.", e);
        }
      }
      return imgfile;
    }

    /**
     * Get the relation
     * 
     * @return the relation
     */
    public Relation<?> getRelation() {
      return relation;
    }

    /**
     * Get the IDs
     * 
     * @return the ids
     */
    public ArrayDBIDs getIDs() {
      return ids;
    }

    @Override
    public String getLongName() {
      return "Similarity Matrix";
    }

    @Override
    public String getShortName() {
      return "sim-matrix";
    }
  }

  /**
   * Parameterization class.
   * 
   * @author Erich Schubert
   * 
   * @apiviz.exclude
   */
  public static class Parameterizer<O> extends AbstractParameterizer {
    /**
     * The distance function to use
     */
    private DistanceFunction<O, ? extends NumberDistance<?, ?>> distanceFunction;

    /**
     * Scaling function to use
     */
    private ScalingFunction scaling;

    /**
     * Skip zero values.
     */
    private boolean skipzero = false;

    @Override
    protected void makeOptions(Parameterization config) {
      super.makeOptions(config);
      ObjectParameter<DistanceFunction<O, ? extends NumberDistance<?, ?>>> distanceFunctionP = AbstractAlgorithm.makeParameterDistanceFunction(EuclideanDistanceFunction.class, DistanceFunction.class);
      if(config.grab(distanceFunctionP)) {
        distanceFunction = distanceFunctionP.instantiateClass(config);
      }

      ObjectParameter<ScalingFunction> scalingP = new ObjectParameter<ScalingFunction>(SCALING_ID, ScalingFunction.class, true);
      if(config.grab(scalingP)) {
        scaling = scalingP.instantiateClass(config);
      }

      Flag skipzeroP = new Flag(SKIPZERO_ID);
      if(config.grab(skipzeroP)) {
        skipzero = skipzeroP.getValue();
      }
    }

    @Override
    protected ComputeSimilarityMatrixImage<O> makeInstance() {
      return new ComputeSimilarityMatrixImage<O>(distanceFunction, scaling, skipzero);
    }
  }
}