/*
 * This file is part of ELKI:
 * Environment for Developing KDD-Applications Supported by Index-Structures
 *
 * Copyright (C) 2019
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
package elki.outlier.distance.parallel;

import elki.AbstractDistanceBasedAlgorithm;
import elki.data.type.TypeInformation;
import elki.data.type.TypeUtil;
import elki.database.datastore.DataStoreFactory;
import elki.database.datastore.DataStoreUtil;
import elki.database.datastore.WritableDoubleDataStore;
import elki.database.ids.DBIDs;
import elki.database.ids.KNNList;
import elki.database.query.QueryBuilder;
import elki.database.query.knn.KNNQuery;
import elki.database.relation.DoubleRelation;
import elki.database.relation.MaterializedDoubleRelation;
import elki.database.relation.Relation;
import elki.distance.Distance;
import elki.logging.Logging;
import elki.math.DoubleMinMax;
import elki.outlier.OutlierAlgorithm;
import elki.outlier.distance.KNNOutlier;
import elki.parallel.ParallelExecutor;
import elki.parallel.processor.DoubleMinMaxProcessor;
import elki.parallel.processor.KDistanceProcessor;
import elki.parallel.processor.KNNProcessor;
import elki.parallel.processor.WriteDoubleDataStoreProcessor;
import elki.parallel.variables.SharedDouble;
import elki.parallel.variables.SharedObject;
import elki.result.outlier.BasicOutlierScoreMeta;
import elki.result.outlier.OutlierResult;
import elki.result.outlier.OutlierScoreMeta;
import elki.utilities.documentation.Reference;
import elki.utilities.optionhandling.parameterization.Parameterization;
import elki.utilities.optionhandling.parameters.IntParameter;

/**
 * Parallel implementation of KNN Outlier detection.
 * <p>
 * Reference:
 * <p>
 * S. Ramaswamy, R. Rastogi, K. Shim<br>
 * Efficient Algorithms for Mining Outliers from Large Data Sets<br>
 * Proc. of the Int. Conf. on Management of Data
 * <p>
 * This parallelized implementation is based on the easy-to-parallelize
 * generalized pattern discussed in
 * <p>
 * Erich Schubert, Arthur Zimek, Hans-Peter Kriegel<br>
 * Local Outlier Detection Reconsidered: a Generalized View on Locality with
 * Applications to Spatial, Video, and Network Outlier Detection<br>
 * Data Mining and Knowledge Discovery 28(1)
 *
 * @author Erich Schubert
 * @since 0.7.0
 *
 * @composed - - - KNNProcessor
 * @composed - - - KDistanceProcessor
 *
 * @param <O> Object type
 */
@Reference(authors = "Erich Schubert, Arthur Zimek, Hans-Peter Kriegel", //
    title = "Local Outlier Detection Reconsidered: a Generalized View on Locality with Applications to Spatial, Video, and Network Outlier Detection", //
    booktitle = "Data Mining and Knowledge Discovery 28(1)", //
    url = "https://doi.org/10.1007/s10618-012-0300-z", //
    bibkey = "DBLP:journals/datamine/SchubertZK14")
public class ParallelKNNOutlier<O> extends AbstractDistanceBasedAlgorithm<Distance<? super O>, OutlierResult> implements OutlierAlgorithm {
  /**
   * Parameter k + 1
   */
  private int kplus;

  /**
   * Constructor.
   * 
   * @param distance Distance function
   * @param k K parameter
   */
  public ParallelKNNOutlier(Distance<? super O> distance, int k) {
    super(distance);
    this.kplus = k + 1;
  }

  /**
   * Class logger
   */
  private static final Logging LOG = Logging.getLogger(ParallelKNNOutlier.class);

  @Override
  public TypeInformation[] getInputTypeRestriction() {
    return TypeUtil.array(getDistance().getInputTypeRestriction());
  }

  /**
   * Run the parallel kNN outlier detector.
   *
   * @param relation Relation to analyze
   * @return Outlier detection result
   */
  public OutlierResult run(Relation<O> relation) {
    DBIDs ids = relation.getDBIDs();
    WritableDoubleDataStore store = DataStoreUtil.makeDoubleStorage(ids, DataStoreFactory.HINT_DB);
    KNNQuery<O> knnq = new QueryBuilder<>(relation, distance).kNNQuery(kplus);

    // Compute the kNN
    KNNProcessor<O> knnm = new KNNProcessor<>(kplus, knnq);
    SharedObject<KNNList> knnv = new SharedObject<>();
    knnm.connectKNNOutput(knnv);
    // Extract the k-distance
    KDistanceProcessor kdistm = new KDistanceProcessor(kplus);
    SharedDouble kdistv = new SharedDouble();
    kdistm.connectKNNInput(knnv);
    kdistm.connectOutput(kdistv);
    // Store in outlier scores
    WriteDoubleDataStoreProcessor storem = new WriteDoubleDataStoreProcessor(store);
    storem.connectInput(kdistv);
    // Gather statistics
    DoubleMinMaxProcessor mmm = new DoubleMinMaxProcessor();
    mmm.connectInput(kdistv);

    ParallelExecutor.run(ids, knnm, kdistm, storem, mmm);

    DoubleMinMax minmax = mmm.getMinMax();
    DoubleRelation scoreres = new MaterializedDoubleRelation("kNN Outlier Score", ids, store);
    OutlierScoreMeta meta = new BasicOutlierScoreMeta(minmax.getMin(), minmax.getMax(), 0.0, Double.POSITIVE_INFINITY, 0.0);
    return new OutlierResult(meta, scoreres);
  }

  @Override
  protected Logging getLogger() {
    return LOG;
  }

  /**
   * Parameterization class
   * 
   * @author Erich Schubert
   * 
   * @hidden
   *
   * @param <O> Object type
   */
  public static class Par<O> extends AbstractDistanceBasedAlgorithm.Par<Distance<? super O>> {
    /**
     * K parameter
     */
    int k;

    @Override
    public void configure(Parameterization config) {
      super.configure(config);
      new IntParameter(KNNOutlier.Par.K_ID) //
          .grab(config, x -> k = x);
    }

    @Override
    public ParallelKNNOutlier<O> make() {
      return new ParallelKNNOutlier<>(distance, k);
    }
  }
}