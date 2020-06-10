package edu.rice.cs

import com.mongodb.casbah.commons.MongoDBObject
import com.mongodb.casbah.{MongoClient, MongoClientURI}
import org.apache.spark.SparkConf
import org.apache.spark.ml.evaluation.RegressionEvaluator
import org.apache.spark.ml.feature.StringIndexer
import org.apache.spark.mllib.recommendation.MatrixFactorizationModel
import org.apache.spark.sql.DataFrame
//import org.apache.spark.ml.recommendation.ALS
import org.apache.spark.mllib.recommendation.{Rating => ALSRating, ALS}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession
import org.jblas.DoubleMatrix

object ALSRecommender {
  // online db
//  val config = Map(
//    "spark.cores" -> "local[*]",
//    "mongo.uri" -> "mongodb+srv://amazon:amazon666@cluster0-u2qt7.mongodb.net/amazon_recommender?retryWrites=true&w=majority",
//    "mongo.db" -> "amazon_recommender"
//  )

  // test db
  val config = Map(
    "spark.cores" -> "local[*]",
    "mongo.uri" -> "mongodb+srv://amazon:amazon666@cluster0-u2qt7.mongodb.net/test?retryWrites=true&w=majority",
    "mongo.db" -> "test"
  )

//  val REVIEW_COLLECTION = "review"
  val RATING_COLLECTION = "rating"
  val USER_REC_COLLECTION = "als_recommendation"
  val PRODUCT_SIM_COLLECTION = "product_similarity"
  val RECOMMEND_NUM = 20

  def main(args: Array[String]): Unit = {

    // create a spark config
    val sparkConf = new SparkConf().setMaster(config("spark.cores")).setAppName("ALSRecommender")
    // create a spark session
    val spark = SparkSession.builder().config(sparkConf).getOrCreate()
    val sc = spark.sparkContext
    sc.setLogLevel("WARN")

    import spark.implicits._
    implicit val mongoConfig = MongoConfig(config("mongo.uri"), config("mongo.db"))
    // load rating
    val ratingRDD = spark.read
      .option("uri", mongoConfig.uri)
      .option("collection", RATING_COLLECTION)
      .format("com.mongodb.spark.sql")
      .load()
      .as[Rating]
      .rdd
      .cache()
//    ratingRDD.show(10)
//    ratingRDD.take(10).foreach(println)

    // create id map from string to int
    val userIdMap = ratingRDD.map(_.userId).distinct().zipWithUniqueId().collectAsMap()
    val userIdMapRev = userIdMap.map{case (s, i) => (i, s)}
    val productIdMap = ratingRDD.map(_.productId).distinct().zipWithUniqueId().collectAsMap()
    val productIdMapRev = productIdMap.map{case (s, i) => (i, s)}
    val ratingTransRDD = ratingRDD
      .map(r => ALSRating(userIdMap(r.userId).toInt, productIdMap(r.productId).toInt, r.rating))

    // convert userId, productId to int
//    val stringIndexerUser = new StringIndexer()
//      .setInputCol("reviewerID")
//      .setOutputCol("userID")
//    var indexer = stringIndexerUser.fit(ratingRDD)
//    var ratingDF = indexer.transform(ratingRDD)
//
//    val stringIndexerProd = new StringIndexer()
//      .setInputCol("asin")
//      .setOutputCol("productId")
//    indexer = stringIndexerProd.fit(ratingDF)
//    ratingDF = indexer.transform(ratingDF)
//    ratingDF.show(10)

//    val als = new ALS()
//      .setRank(5)    // latent features dimensions
//      .setMaxIter(10)  // max number of iterations
//      .setRegParam(0.05)  // regularization
//      .setUserCol("reviewerID")
//      .setItemCol("asin")
//      .setRatingCol("overall")
//    val model = als.fit(trainData)
//
//    // evaluate test data
//    // set cold start strategy to "drop" to ensure no NAN evaluation metrics
////    model.setColdStartStrategy("drop")
////    val userRecs = model.recommendForAllUsers(RECOMMEND_NUM).toDF()
////    val productRecs = model.recommendForAllItems(1)
////    userRecs.printSchema()
////    userRecs.show()
//

    // train ALS model
    val Array(trainData, testData) = ratingTransRDD.randomSplit(Array(0.8, 0.2))
    val (rank, iterations, lambda) = (10, 10, 0.05)
    val model = ALS.train(trainData, rank, iterations, lambda)

    // evaluate test data
    val userProductsTest = testData.map(r => (r.user, r.product))
    val predRDD = model.predict(userProductsTest).map(
      item => ((item.user, item.product), item.rating)
    )
    val testRDD = testData.map(
      item => ((item.user, item.product), item.rating))
    val predDF = predRDD.join(testRDD)
      .map(
        item => (item._1._1, item._1._2, item._2._1, item._2._2)
      ).toDF("userId", "productId", "prediction", "rating")
//    predDF.show(10)
    val evaluator = new RegressionEvaluator()
      .setMetricName("rmse")
      .setLabelCol("rating")
      .setPredictionCol("prediction")
    val rmse = evaluator.evaluate(predDF)
    println(s"rmse: $rmse")

    val userRDD = ratingTransRDD.map(_.user).distinct()
    val productRDD = ratingTransRDD.map(_.product).distinct()
    val userRecDF = recommendTopK(userRDD, productRDD, model, userIdMapRev, productIdMapRev).toDF()
    val productRecDF = computeProductSimMatrix(model, productIdMapRev).toDF()
    saveToMongoDB(userRecDF, USER_REC_COLLECTION, "userId")
    saveToMongoDB(productRecDF, PRODUCT_SIM_COLLECTION, "productId")

    spark.stop()
  }

  // recommend top-k products for all users
  def recommendTopK(userRDD: RDD[Int],
                    productRDD: RDD[Int],
                    model: MatrixFactorizationModel,
                    userIdMapRev: scala.collection.Map[Long, String],
                    productIdMapRev: scala.collection.Map[Long, String]
                   ) : RDD[UserRecList] = {


    val userProducts = userRDD.cartesian(productRDD)
    val preds = model.predict(userProducts)
//    val userRecs = model.recommendProductsForUsers(RECOMMEND_NUM).toDF("userId", "recommendations")
    val userRec = preds.filter(_.rating > 0)
      .map(
        rating => (rating.user, (rating.product, rating.rating))
      )
      .groupByKey()
      .map {
        case (userId, recs) =>
          UserRecList(userIdMapRev(userId),
            recs.toList.sortWith(_._2 > _._2).take(RECOMMEND_NUM).map(x => RecommendItem(productIdMapRev(x._1), x._2)))
      }
    userRec
  }

  // compute similarity between products using product features
  def computeProductSimMatrix(model: MatrixFactorizationModel,
                              productIdMapRev: scala.collection.Map[Long, String]): RDD[ProductRecList] = {
    val productFeatures = model.productFeatures.map {
      case (productId, features) => (productId, new DoubleMatrix(features))
    }
    val productRecDF = productFeatures.cartesian(productFeatures)
      .filter {
        // filter self-self pairs
        case (a, b) => a._1 != b._1
      }
      .map {
        case (a, b) =>
          val sim = a._2.dot(b._2) / (a._2.norm2() * b._2.norm2())
          (a._1, (b._1, sim))
      }
      .filter(_._2._2 > 0.5)
      .groupByKey()
      .map {
        case (productId, recs) =>
          ProductRecList(productIdMapRev(productId), recs.toList.sortWith(_._2 > _._2).map(x => RecommendItem(productIdMapRev(x._1), x._2)))
      }
    productRecDF
  }

  def saveToMongoDB(df: DataFrame, collectionName: String, index: String)(implicit mongoConfig: MongoConfig): Unit = {
    val mongoClient = MongoClient(MongoClientURI(mongoConfig.uri))

    val mongoCollection = mongoClient(mongoConfig.db)(collectionName)
    mongoCollection.dropCollection()

    df.show()
    df.write
      .option("uri", mongoConfig.uri)
      .option("collection", collectionName)
      .mode("overwrite")
      .format("com.mongodb.spark.sql")
      .save()

    mongoCollection.createIndex(MongoDBObject(index -> 1))
    mongoClient.close()
  }

}
