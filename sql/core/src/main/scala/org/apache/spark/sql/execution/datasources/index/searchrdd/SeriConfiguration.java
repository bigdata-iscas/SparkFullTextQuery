package org.apache.spark.sql.execution.datasources.index.searchrdd;

import org.apache.hadoop.conf.Configuration;

import java.io.Serializable;

/**
 * Created by cuiguangfan on 16-10-31.
 */
public class SeriConfiguration extends Configuration implements Serializable {
    public SeriConfiguration(Configuration other) {
        super(other);
        //other.set("fs.hdfs.impl.disable.cache", "true");
    }
}
