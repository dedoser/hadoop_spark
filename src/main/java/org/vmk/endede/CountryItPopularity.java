package org.vmk.endede;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.apache.commons.lang3.StringUtils;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.broadcast.Broadcast;
import scala.Tuple2;

import java.util.Map;
import java.util.Set;

/**
 * Оригинал запроса
 * <br>
 * create view if not exists loc_actions as
 *   select lower(trim(split(u.location, ',')[0])) loc from users as u
 *   join comments as c on c.userId = u.id
 *   union all
 *   select lower(trim(split(u.location, ',')[0])) loc from users as u
 *   join posts as p on p.owneruserid = u.id;
 * <br>
 * create view if not exists map_loc_act as
 *   select case
 *     when loc = 'new york' or loc = 'united states' or loc = 'usa' or loc = 'california' or loc = 'seattle' then 'usa'
 *     when loc = 'london' or loc = 'united kingdom' or loc = 'uk' then 'uk'
 *     else loc
 *   end as loc
 *   from loc_actions
 *   where length(loc) != 0;
 * <br>
 * select loc, count(*) from map_loc_act
 * group by loc
 * order by count(*) desc
 * <p>
 * Запуск - spark-submit --class org.vmk.endede.CountryItPopularity
 * --master yarn --deploy-mode cluster --driver-memory 1024M --executor-memory 1024M --executor-cores 2 --num-executors 4
 * spark-hadoop-1.0-SNAPSHOT.jar
 * /user/stud/endede/meta_stackexchange/landing/Users
 * /user/stud/endede/meta_stackexchange/landing/Comments
 * /user/stud/endede/meta_stackexchange/landing/Posts
 * /user/stud/endede/spark_output
 * <p>
 * Запуск из директории /home/stud/endede
 */
public class CountryItPopularity {

    private static final Map<String, Set<String>> LOCATION_TO_REPLACE = ImmutableMap.of(
            "usa", ImmutableSet.of(
                    "new york",
                    "united states",
                    "usa",
                    "california",
                    "seattle"),
            "uk", ImmutableSet.of(
                    "london",
                    "united kingdom",
                    "uk")
    );

    private static boolean filterRow(Map<String, String> row, String... fields) {
        for (String field: fields) {
            if (StringUtils.isBlank(row.get(field))) {
                return false;
            }
        }
        return true;
    }

    private static Tuple2<String, Integer> replaceLocations(Tuple2<String, Integer> tuple,
                                                            Broadcast<Map<String, Set<String>>> bc) {
        String location = tuple._1;
        for (Map.Entry<String, Set<String>> entry: bc.getValue().entrySet()) {
            Set<String> replacements = entry.getValue();
            if (replacements.contains(location)) {
                location = entry.getKey();
                break;
            }
        }
        return new Tuple2<>(location, tuple._2);
    }

    public static void main(String[] args) {
        if (args.length != 4) {
            System.out.println("Wrong amount of arguments");
            System.exit(1);
        }

        String usersPath = args[0];
        String commentsPath = args[1];
        String postsPath = args[2];
        String outPath = args[3];

        SparkConf sparkConf = new SparkConf()
                .setAppName("Country IT Popularity");
        JavaSparkContext sc = new JavaSparkContext(sparkConf);

        JavaPairRDD<String, String> uidToLocation = sc.textFile(usersPath)
                .map(XmlUtils::parseXmlToMap)
                .filter(row -> filterRow(row, "Id", "Location"))
                .mapToPair(row -> new Tuple2<>(row.get("Id"), row.get("Location")))
                .mapValues(loc ->
                        loc.split(",")[0]
                                .trim()
                                .toLowerCase()
                );
        JavaPairRDD<String, Integer> comments = sc.textFile(commentsPath)
                .map(XmlUtils::parseXmlToMap)
                .filter(row -> filterRow(row, "UserId"))
                .mapToPair(row -> new Tuple2<>(row.get("UserId"), 1));

        JavaPairRDD<String, Integer> posts = sc.textFile(postsPath)
                .map(XmlUtils::parseXmlToMap)
                .filter(row -> filterRow(row, "OwnerUserId"))
                .mapToPair(row -> new Tuple2<>(row.get("OwnerUserId"), 1));

        JavaPairRDD<String, Integer> locationToComments = uidToLocation.join(comments)
                .mapToPair(Tuple2::_2)
                .reduceByKey(Integer::sum);

        JavaPairRDD<String, Integer> locationToPosts = uidToLocation.join(posts)
                .mapToPair(Tuple2::_2)
                .reduceByKey(Integer::sum);

        Broadcast<Map<String, Set<String>>> bc = sc.broadcast(LOCATION_TO_REPLACE);

        locationToPosts.union(locationToComments)
                .mapToPair(pair -> replaceLocations(pair, bc))
                .reduceByKey(Integer::sum)
                .mapToPair(Tuple2::swap)
                .sortByKey(false)
                .coalesce(1)
                .saveAsTextFile(outPath);

        sc.close();
    }
}