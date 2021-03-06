package matrix.factorization.model

import matrix.factorization.types._
import matrix.factorization.LEMP._
import matrix.factorization.LEMP.PruningFunctions.{coordPruning, incrPruning, lengthPruning}
import matrix.factorization.initializer.RangedRandomFactorInitializerDescriptor
import scala.collection.mutable
import scala.util.control.Breaks.{break, breakable}

class LEMP(numFactors: Int, rangeMin: Double, rangeMax: Double, bucketSize: Int,
           K: Int, pruningStrategy: PruningStrategy) extends ModelState[ItemId, Vector]{


  /**
    * Defines how to init new vectors
    */
  lazy val factorInitDesc = RangedRandomFactorInitializerDescriptor(numFactors, rangeMin, rangeMax)

  /**
    * Every id - vector pair
    */
  private val model = new mutable.HashMap[ItemId, Vector]()

  /**
    * Every item vector, sorted by their lengths
    */
  private val itemIdsDescendingByLength = new mutable.TreeSet[ItemVector]()

  private var ids: Set[ItemId] = Set()

  //TODO Check logic
  /**
    * LEMP algo, tests later
    * @param userVector
    * @param pruning
    * @return
    */
  def generateFocusSet(userVector: Vector, pruning: PruningStrategy): (Int, Array[Int]) = {
    val focus = ((1 until userVector.value.length) :\ 0) { (i, f) =>
      if (userVector.value(i) * userVector.value(i) > userVector.value(f) * userVector.value(f))
        i
      else
        f
    }

    // focus coordinate set for incremental pruning test
    val focusSet = Array.range(0, userVector.value.length - 1)
      .sortBy{ x => -userVector.value(x) * userVector.value(x) }
      .take(pruning match {
        case INCR(x) => x
        case LI(x, _)=> x
        case _=> 0
      })

    (focus, focusSet)
  }

  /**
    * LEMP algo, write tests later
    * @param topK
    * @param currentBucket
    * @param pruning
    * @param focus
    * @param focusSet
    * @param userVector
    * @return
    */
   private def pruneCandidateSet(topK: TopK, currentBucket: List[ItemVector], pruning: PruningStrategy,
                        focus: ItemId, focusSet: Array[ItemId], userVector: Vector): List[ItemVector] = {
    val theta = if (topK.length < K) 0.0 else topK.head.score
    val theta_b_q = theta / (currentBucket.head.vector.norm * userVector.norm)
    val vectors = currentBucket



    vectors.filter(
      pruning match {
        case LENGTH() => lengthPruning(theta / userVector.norm)
        case COORD() => coordPruning(focus, userVector, theta_b_q)
        case INCR(_) => incrPruning(focusSet, userVector, theta)
        case LC(threshold) =>
          if (currentBucket.head.vector.norm > currentBucket.last.vector.norm * threshold)
            lengthPruning(theta / userVector.norm)
          else
            coordPruning(focus, userVector, theta_b_q)
        case LI(_, threshold) =>
          if (currentBucket.head.vector.norm > currentBucket.last.vector.norm * threshold)
            lengthPruning(theta / userVector.norm)
          else
            incrPruning(focusSet, userVector, theta)
      })
  }

  /**
    * Return the top k most similar vectors for the query vector
    * @param userVector
    * @return
    */
  def generateTopK(userVector: Vector): TopK = {
    val topK = createTopK
    val buckets = itemIdsDescendingByLength.toList.grouped(bucketSize)

    val userVectorLength = userVector.norm


    breakable {
      for (currentBucket <- buckets) {
        if ( !(topK.length < K || currentBucket.head.vector.norm * userVectorLength >= topK.head.score )) {
          break()
        }
        val (focus, focusSet) =  generateFocusSet(userVector, pruningStrategy)

        val candidates = pruneCandidateSet(topK, currentBucket, pruningStrategy, focus, focusSet, userVector)

        for (item <- candidates) {
          val userItemDotProduct = Vector.dotProduct(userVector, item.vector)

          if (topK.size < K) {
            topK += Prediction(item.id, userItemDotProduct)
          }
          else {
            if (topK.head.score < userItemDotProduct) {
              topK.dequeue
              topK += Prediction(item.id, userItemDotProduct)
            }
          }
        }
      }
    }
    topK
  }

  /**
    * Binary operator, on how to combine two parameter
    *
    * @return The combined value of the two parameter
    */
  override def updateFunction: (Vector, Vector) => Vector =
    Vector.vectorSum

  /**
    * Defines how an element should be initialized
    *
    * @return
    */
  override def initFunction: ItemId => Vector = newItemId =>
    Vector(factorInitDesc.open().nextFactor(newItemId))


  /**
    * Returns all the IDs
    * @return
    */
  override def keys: Array[ItemId] =
    ids.toArray

  /**
    * Returns the corresponding value, if it doesn't exist, then None
    *
    * @param key
    * @return
    */
  override def get(key: ItemId): Option[Vector] =
    model.get(key)

  /**
    * Set the value of key to newValue. Override if there is already one.
    *
    * @param key
    * @param newValue
    */
  override def set(key: ItemId, newValue: Vector): Unit = model.get(key) match {
    case Some(oldValue) =>
      model.update(key, newValue)
      updateItemIdsByLength(key, newValue, oldValue)
    case None =>
      model.update(key, newValue)
      itemIdsDescendingByLength.add(ItemVector(key, newValue))
      ids = ids + key
  }

  /**
    * Remove the previous length for the given id, and add the new one
    * @param key
    * @param updatedValue
    * @param oldValue
    */
  private def updateItemIdsByLength(key: ItemId, updatedValue: Vector, oldValue: Vector): Unit = {
    itemIdsDescendingByLength.remove(ItemVector(key, oldValue))
    itemIdsDescendingByLength.add(ItemVector(key, updatedValue))
  }
}