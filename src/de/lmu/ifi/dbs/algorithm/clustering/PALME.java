package de.lmu.ifi.dbs.algorithm.clustering;

import de.lmu.ifi.dbs.algorithm.AbstractAlgorithm;
import de.lmu.ifi.dbs.algorithm.result.Result;
import de.lmu.ifi.dbs.data.ClassLabel;
import de.lmu.ifi.dbs.data.DatabaseObject;
import de.lmu.ifi.dbs.data.MultiRepresentedObject;
import de.lmu.ifi.dbs.database.AssociationID;
import de.lmu.ifi.dbs.database.Database;
import de.lmu.ifi.dbs.distance.Distance;
import de.lmu.ifi.dbs.distance.RepresentationSelectingDistanceFunction;
import de.lmu.ifi.dbs.utilities.Description;
import de.lmu.ifi.dbs.utilities.Progress;
import de.lmu.ifi.dbs.utilities.QueryResult;
import de.lmu.ifi.dbs.utilities.Util;

import java.util.*;

/**
 * TODO: comment and new name
 *
 * @author Elke Achtert (<a href="mailto:achtert@dbs.ifi.lmu.de">achtert@dbs.ifi.lmu.de</a>)
 */
public class PALME<O extends DatabaseObject, D extends Distance<D>, M extends MultiRepresentedObject<O>> extends AbstractAlgorithm<M> {

  /**
   * The distance function for the single representations.
   */
  private RepresentationSelectingDistanceFunction<O, M, D> mr_distanceFunction;

  public PALME() {
    super();
  }

  /**
   * The run method encapsulated in measure of runtime. An extending class
   * needs not to take care of runtime itself.
   *
   * @param database the database to run the algorithm on
   * @throws IllegalStateException if the algorithm has not been initialized properly (e.g. the
   *                               setParameters(String[]) method has been failed to be called).
   */
  protected void runInTime(Database<M> database) throws IllegalStateException {
    try {
      System.out.println("database.size " + database.size());
      mr_distanceFunction.setDatabase(database, isVerbose());

      Progress progress = new Progress(database.size());

      Iterator<Integer> it = database.iterator();
      if (! it.hasNext()) {
        // todo empty result
        return;
      }


      Map<ClassLabel, Set<Integer>> classMap = determineClassPartitions(database);
//      System.out.println(classMap);

      int numberOfRepresentations = database.get(it.next()).getNumberOfRepresentations();

      for (int r = 0; r < numberOfRepresentations; r++) {
        Map<Integer, D> recallRanges = new HashMap<Integer,D>();
        Map<Integer, D> inverseRecallRanges = new HashMap<Integer,D>();

        mr_distanceFunction.setCurrentRepresentationIndex(r);
        D maxDist = mr_distanceFunction.nullDistance();

        it = database.iterator();
        while (it.hasNext()) {
          Integer id = it.next();

          ClassLabel classLabel = (ClassLabel) database.getAssociation(AssociationID.CLASS, id);
          Set<Integer> desired = classMap.get(classLabel);

          List<QueryResult<D>> neighbors = database.rangeQuery(id, RepresentationSelectingDistanceFunction.INFINITY_PATTERN, mr_distanceFunction);
          maxDist = Util.max(maxDist, neighbors.get(neighbors.size() - 1).getDistance());

          D recall = getRecallRange(desired, neighbors);
          recallRanges.put(id, recall);

          D inverseRecall = getInverseRecallRange(desired, neighbors);
          inverseRecallRanges.put(id, inverseRecall);

          System.out.println("  " + id + " recallRange    " + recall);
          System.out.println("  " + id + " invRecallRange " + inverseRecall);
        }

        System.out.println("");
        System.out.println("Representation " + r);
        System.out.println("  recallRanges " + recallRanges);
        System.out.println("  invRecallRanges " + inverseRecallRanges);
        System.out.println("  maxDist     " + maxDist);
      }


    }
    catch (Exception e) {
      e.printStackTrace();
      throw new IllegalStateException(e);
    }
  }

  /**
   * @see de.lmu.ifi.dbs.utilities.optionhandling.Parameterizable#setParameters(String[])
   */
  public String[] setParameters(String[] args) throws IllegalArgumentException {
    String[] remainungParams = super.setParameters(args);
    mr_distanceFunction = new RepresentationSelectingDistanceFunction<O, M, D>();
    return mr_distanceFunction.setParameters(remainungParams);
  }

  /**
   * Returns the result of the algorithm.
   *
   * @return the result of the algorithm
   */
  public Result<M> getResult() {
    // todo
    return null;  //To change body of implemented methods use File | Settings | File Templates.
  }

  /**
   * Returns a description of the algorithm.
   *
   * @return a description of the algorithm
   */
  public Description getDescription() {
    // todo
    return new Description("PALME",
                           "algorithm without name ;-)",
                           "First it determines the Recall and Precision levels for each representation " +
                           "of a database containing multirepresented_old objects according to a training set. " +
                           "Second a multirepresented_old version of OPTICS will be applied.",
                           "unpublished");
  }

  private Map<ClassLabel, Set<Integer>> determineClassPartitions(Database<M> database) throws IllegalStateException {
    Map<ClassLabel, Set<Integer>> classMap = new HashMap<ClassLabel, Set<Integer>>();

    Iterator<Integer> it = database.iterator();
    while (it.hasNext()) {
      Integer id = it.next();
      ClassLabel classLabel = (ClassLabel) database.getAssociation(AssociationID.CLASS, id);

      Set<Integer> ids = classMap.get(classLabel);
      if (ids == null) {
        ids = new HashSet<Integer>();
        classMap.put(classLabel, ids);
      }
      ids.add(id);

    }

    return classMap;
  }

  private List<D> getPrecisionAndRecallRange(Set<Integer> desiredSet, List<QueryResult<D>> neighbors, Database<M> db) {
    System.out.println("neighbors " + neighbors);

    if (neighbors.isEmpty())
      throw new IllegalArgumentException("Empty neighbors!");

    D precisionRange = mr_distanceFunction.nullDistance();
    D recallRange = mr_distanceFunction.infiniteDistance();
    D currentRange = mr_distanceFunction.nullDistance();

    double found = 0;
    double desired = desiredSet.size();
    double foundAndDesired = 0;

    System.out.println("desiredSet " + desiredSet);

    for (QueryResult<D> neighbor : neighbors) {
      found ++;
      if (desiredSet.contains(neighbor.getID())) {
        foundAndDesired++;
      }

      double p = foundAndDesired / found;
      double r = foundAndDesired / desired;

      System.out.println("");

      M object = db.get(neighbor.getID());
      System.out.println("  r_" + 0 + ": " + neighbor.getID() + " " + object.getRepresentation(0));


      System.out.println(" " + neighbor);

      System.out.println(" " + neighbor.getID() + " " + db.getAssociation(AssociationID.CLASS, neighbor.getID()));
      System.out.println(" foundAndDesired " + foundAndDesired);
      System.out.println(" found " + found);
      System.out.println(" desired " + desired);
      System.out.println(" p = " + p);
      System.out.println(" r = " + r);

      if (p > 0.90) {
//        System.out.println("");
//        System.out.println("foundAndDesired " + foundAndDesired);
//        System.out.println("found " + found);
        precisionRange = Util.max(precisionRange, currentRange);
      }

      if (r > 0.90) {
//        System.out.println("");
//        System.out.println("foundAndDesired " + foundAndDesired);
//        System.out.println("desired " + desired);
        recallRange = Util.min(recallRange, neighbor.getDistance());
      }

      currentRange = neighbor.getDistance();
    }

    List<D> result = new ArrayList<D>();
    result.add(precisionRange);
    result.add(recallRange);
    return result;
  }

  private D getRecallRange(Set<Integer> desiredSet, List<QueryResult<D>> neighbors) {
    if (neighbors.isEmpty())
      throw new IllegalArgumentException("Empty neighbors!");

    double desired = desiredSet.size();
    double foundAndDesired = 0;

    for (QueryResult<D> neighbor : neighbors) {
      if (desiredSet.contains(neighbor.getID())) {
        foundAndDesired++;
      }
      double r = foundAndDesired / desired;

      if (r >= 0.90) {
        return neighbor.getDistance();
      }
    }
    throw new IllegalStateException("Recall > 0.90  not found!");
  }

  private D getInverseRecallRange(Set<Integer> desiredSet, List<QueryResult<D>> neighbors) {
    if (neighbors.isEmpty())
      throw new IllegalArgumentException("Empty neighbors!");

    double notDesired = neighbors.size() - desiredSet.size();
    double notFoundAndNotDesired = 0;

    for (int i = neighbors.size() - 1; i >= 0; i--) {
      QueryResult<D> neighbor = neighbors.get(i);
      if (! desiredSet.contains(neighbor.getID())) {
        notFoundAndNotDesired++;
      }
      double ir = notFoundAndNotDesired / notDesired;

      if (ir >= 0.90) {
        return neighbor.getDistance();
      }
    }
    throw new IllegalStateException("Inverse Recall > 0.90  not found!");
  }

}
