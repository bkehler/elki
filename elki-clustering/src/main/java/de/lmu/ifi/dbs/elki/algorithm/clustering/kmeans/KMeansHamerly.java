/*
 * This file is part of ELKI:
 * Environment for Developing KDD-Applications Supported by Index-Structures
 *
 * Copyright (C) 2018
 * ELKI Development Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package de.lmu.ifi.dbs.elki.algorithm.clustering.kmeans;

import java.util.ArrayList;
import java.util.List;

import de.lmu.ifi.dbs.elki.algorithm.clustering.kmeans.initialization.KMeansInitialization;
import de.lmu.ifi.dbs.elki.data.Clustering;
import de.lmu.ifi.dbs.elki.data.DoubleVector;
import de.lmu.ifi.dbs.elki.data.NumberVector;
import de.lmu.ifi.dbs.elki.data.model.KMeansModel;
import de.lmu.ifi.dbs.elki.database.Database;
import de.lmu.ifi.dbs.elki.database.datastore.DataStoreFactory;
import de.lmu.ifi.dbs.elki.database.datastore.DataStoreUtil;
import de.lmu.ifi.dbs.elki.database.datastore.WritableDoubleDataStore;
import de.lmu.ifi.dbs.elki.database.datastore.WritableIntegerDataStore;
import de.lmu.ifi.dbs.elki.database.ids.DBIDIter;
import de.lmu.ifi.dbs.elki.database.ids.DBIDUtil;
import de.lmu.ifi.dbs.elki.database.ids.ModifiableDBIDs;
import de.lmu.ifi.dbs.elki.database.relation.Relation;
import de.lmu.ifi.dbs.elki.distance.distancefunction.NumberVectorDistanceFunction;
import de.lmu.ifi.dbs.elki.distance.distancefunction.minkowski.SquaredEuclideanDistanceFunction;
import de.lmu.ifi.dbs.elki.logging.Logging;
import de.lmu.ifi.dbs.elki.logging.progress.IndefiniteProgress;
import de.lmu.ifi.dbs.elki.logging.statistics.LongStatistic;
import de.lmu.ifi.dbs.elki.logging.statistics.StringStatistic;
import de.lmu.ifi.dbs.elki.math.linearalgebra.VMath;
import de.lmu.ifi.dbs.elki.utilities.documentation.Reference;
import de.lmu.ifi.dbs.elki.utilities.optionhandling.parameterization.Parameterization;

import net.jafama.FastMath;

/**
 * Hamerly's fast k-means by exploiting the triangle inequality.
 * <p>
 * Reference:
 * <p>
 * G. Hamerly<br>
 * Making k-means even faster<br>
 * Proc. 2010 SIAM International Conference on Data Mining
 *
 * @author Erich Schubert
 * @since 0.7.0
 *
 * @apiviz.has KMeansModel
 *
 * @param <V> vector datatype
 */
@Reference(authors = "G. Hamerly", //
    title = "Making k-means even faster", //
    booktitle = "Proc. 2010 SIAM International Conference on Data Mining", //
    url = "https://doi.org/10.1137/1.9781611972801.12", //
    bibkey = "DBLP:conf/sdm/Hamerly10")
public class KMeansHamerly<V extends NumberVector> extends AbstractKMeans<V, KMeansModel> {
  /**
   * The logger for this class.
   */
  private static final Logging LOG = Logging.getLogger(KMeansHamerly.class);

  /**
   * Key for statistics logging.
   */
  private static final String KEY = KMeansHamerly.class.getName();

  /**
   * Flag whether to compute the final variance statistic.
   */
  protected boolean varstat = false;

  /**
   * Constructor.
   *
   * @param distanceFunction distance function
   * @param k k parameter
   * @param maxiter Maxiter parameter
   * @param initializer Initialization method
   * @param varstat Compute the variance statistic
   */
  public KMeansHamerly(NumberVectorDistanceFunction<? super V> distanceFunction, int k, int maxiter, KMeansInitialization<? super V> initializer, boolean varstat) {
    super(distanceFunction, k, maxiter, initializer);
    this.varstat = varstat;
  }

  @Override
  public Clustering<KMeansModel> run(Database database, Relation<V> relation) {
    if(relation.size() <= 0) {
      return new Clustering<>("k-Means Clustering", "kmeans-clustering");
    }
    // Choose initial means
    LOG.statistics(new StringStatistic(KEY + ".initialization", initializer.toString()));
    double[][] means = initializer.chooseInitialMeans(database, relation, k, getDistanceFunction());
    // Setup cluster assignment store
    List<ModifiableDBIDs> clusters = new ArrayList<>();
    for(int i = 0; i < k; i++) {
      clusters.add(DBIDUtil.newHashSet((int) (relation.size() * 2. / k)));
    }
    WritableIntegerDataStore assignment = DataStoreUtil.makeIntegerStorage(relation.getDBIDs(), DataStoreFactory.HINT_TEMP | DataStoreFactory.HINT_HOT, -1);
    // Hamerly bounds
    WritableDoubleDataStore upper = DataStoreUtil.makeDoubleStorage(relation.getDBIDs(), DataStoreFactory.HINT_TEMP | DataStoreFactory.HINT_HOT, Double.POSITIVE_INFINITY);
    WritableDoubleDataStore lower = DataStoreUtil.makeDoubleStorage(relation.getDBIDs(), DataStoreFactory.HINT_TEMP | DataStoreFactory.HINT_HOT, 0.);
    // Storage for updated means:
    final int dim = means[0].length;
    double[][] sums = new double[k][dim], newmeans = new double[k][dim];
    // Separation of means / distance moved.
    double[] sep = new double[k];

    IndefiniteProgress prog = LOG.isVerbose() ? new IndefiniteProgress("K-Means iteration", LOG) : null;
    LongStatistic rstat = LOG.isStatistics() ? new LongStatistic(KEY + ".reassignments") : null;
    LongStatistic diststat = LOG.isStatistics() ? new LongStatistic(KEY + ".distance-computations") : null;
    int iteration = 0;
    for(; maxiter <= 0 || iteration < maxiter; iteration++) {
      LOG.incrementProcessed(prog);
      int changed;
      if(iteration == 0) {
        changed = initialAssignToNearestCluster(relation, means, sums, clusters, assignment, upper, lower, diststat);
      }
      else {
        recomputeSeperation(means, sep, diststat);
        changed = assignToNearestCluster(relation, means, sums, clusters, assignment, sep, upper, lower, diststat);
      }
      LOG.statistics(rstat != null ? rstat.setLong(changed) : null);
      // Stop if no cluster assignment changed.
      if(changed == 0) {
        break;
      }
      // Recompute means.
      for(int i = 0; i < k; i++) {
        VMath.overwriteTimes(newmeans[i], sums[i], 1. / clusters.get(i).size());
      }
      double delta = movedDistance(means, newmeans, sep);
      updateBounds(relation, assignment, upper, lower, sep, delta);
      for(int i = 0; i < k; i++) {
        System.arraycopy(newmeans[i], 0, means[i], 0, dim);
      }
    }
    LOG.setCompleted(prog);
    LOG.statistics(new LongStatistic(KEY + ".iterations", iteration));
    LOG.statistics(diststat);
    upper.destroy();
    lower.destroy();

    return buildResult(clusters, means, varstat, relation, diststat);
  }

  /**
   * Perform initial cluster assignment.
   *
   * @param relation Data
   * @param means Current means
   * @param sums Running sums of the new means
   * @param clusters Current clusters
   * @param assignment Cluster assignment
   * @param upper Upper bounds
   * @param lower Lower boundsO
   * @return Number of changes (i.e. relation size)
   */
  protected int initialAssignToNearestCluster(Relation<V> relation, double[][] means, double[][] sums, List<ModifiableDBIDs> clusters, WritableIntegerDataStore assignment, WritableDoubleDataStore upper, WritableDoubleDataStore lower, LongStatistic diststat) {
    assert (k == means.length);
    boolean issquared = distanceFunction.isSquared();
    for(DBIDIter it = relation.iterDBIDs(); it.valid(); it.advance()) {
      V fv = relation.get(it);
      // Find closest center, and distance to two closest centers
      double min1 = Double.POSITIVE_INFINITY, min2 = Double.POSITIVE_INFINITY;
      int minIndex = -1;
      for(int i = 0; i < k; i++) {
        double dist = distanceFunction.distance(fv, DoubleVector.wrap(means[i]));
        if(dist < min1) {
          minIndex = i;
          min2 = min1;
          min1 = dist;
        }
        else if(dist < min2) {
          min2 = dist;
        }
      }
      // Assign to nearest cluster.
      clusters.get(minIndex).add(it);
      assignment.putInt(it, minIndex);
      plusEquals(sums[minIndex], fv);
      upper.putDouble(it, issquared ? FastMath.sqrt(min1) : min1);
      lower.putDouble(it, issquared ? FastMath.sqrt(min2) : min2);
    }
    if(diststat != null) {
      diststat.increment(k * relation.size());
    }
    return relation.size();
  }

  /**
   * Reassign objects, but avoid unnecessary computations based on their bounds.
   *
   * @param relation Data
   * @param means Current means
   * @param sums New means as running sums
   * @param clusters Current clusters
   * @param assignment Cluster assignment
   * @param sep Separation of means
   * @param upper Upper bounds
   * @param lower Lower bounds
   * @param diststat Distance statistics
   * @return true when the object was reassigned
   */
  protected int assignToNearestCluster(Relation<V> relation, double[][] means, double[][] sums, List<ModifiableDBIDs> clusters, WritableIntegerDataStore assignment, double[] sep, WritableDoubleDataStore upper, WritableDoubleDataStore lower, LongStatistic diststat) {
    assert (k == means.length);
    final boolean issquared = distanceFunction.isSquared();
    int changed = 0, dists = 0;
    for(DBIDIter it = relation.iterDBIDs(); it.valid(); it.advance()) {
      final int cur = assignment.intValue(it);
      // Compute the current bound:
      final double z = lower.doubleValue(it);
      final double sa = sep[cur];
      double u = upper.doubleValue(it);
      if(u <= z || u <= sa) {
        continue;
      }
      // Update the upper bound
      V fv = relation.get(it);
      double curd2 = distanceFunction.distance(fv, DoubleVector.wrap(means[cur]));
      ++dists;
      u = issquared ? FastMath.sqrt(curd2) : curd2;
      upper.putDouble(it, u);
      if(u <= z || u <= sa) {
        continue;
      }
      // Find closest center, and distance to two closest centers
      double min1 = curd2, min2 = Double.POSITIVE_INFINITY;
      int minIndex = cur;
      --dists; // i == cur will increment below.
      for(int i = 0; i < k; i++) {
        if(i == cur) {
          continue;
        }
        double dist = distanceFunction.distance(fv, DoubleVector.wrap(means[i]));
        ++dists;
        if(dist < min1) {
          minIndex = i;
          min2 = min1;
          min1 = dist;
        }
        else if(dist < min2) {
          min2 = dist;
        }
      }
      if(minIndex != cur) {
        clusters.get(minIndex).add(it);
        clusters.get(cur).remove(it);
        assignment.putInt(it, minIndex);
        plusMinusEquals(sums[minIndex], sums[cur], fv);
        ++changed;
        upper.putDouble(it, min1 == curd2 ? u : issquared ? FastMath.sqrt(min1) : min1);
      }
      lower.putDouble(it, min2 == curd2 ? u : issquared ? FastMath.sqrt(min2) : min2);
    }
    if(diststat != null) {
      diststat.increment(dists);
    }
    return changed;
  }

  /**
   * Update the bounds for k-means.
   *
   * @param relation Relation
   * @param assignment Cluster assignment
   * @param upper Upper bounds
   * @param lower Lower bounds
   * @param move Movement of centers
   * @param delta Maximum center movement.
   */
  protected void updateBounds(Relation<V> relation, WritableIntegerDataStore assignment, WritableDoubleDataStore upper, WritableDoubleDataStore lower, double[] move, double delta) {
    delta = -delta;
    for(DBIDIter it = relation.iterDBIDs(); it.valid(); it.advance()) {
      upper.increment(it, move[assignment.intValue(it)]);
      lower.increment(it, delta);
    }
  }

  @Override
  protected Logging getLogger() {
    return LOG;
  }

  /**
   * Parameterization class.
   *
   * @author Erich Schubert
   *
   * @apiviz.exclude
   */
  public static class Parameterizer<V extends NumberVector> extends AbstractKMeans.Parameterizer<V> {
    @Override
    protected void getParameterDistanceFunction(Parameterization config) {
      super.getParameterDistanceFunction(config);
      if(distanceFunction instanceof SquaredEuclideanDistanceFunction) {
        return; // Proper choice.
      }
      if(distanceFunction != null && !distanceFunction.isMetric()) {
        LOG.warning("Hamerly k-means requires a metric distance, and k-means should only be used with squared Euclidean distance!");
      }
    }

    @Override
    protected void makeOptions(Parameterization config) {
      super.makeOptions(config);
      super.getParameterVarstat(config);
    }

    @Override
    protected KMeansHamerly<V> makeInstance() {
      return new KMeansHamerly<>(distanceFunction, k, maxiter, initializer, varstat);
    }
  }
}
