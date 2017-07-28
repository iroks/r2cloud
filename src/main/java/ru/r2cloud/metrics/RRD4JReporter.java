package ru.r2cloud.metrics;

import static org.rrd4j.ConsolFun.AVERAGE;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.SortedMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.rrd4j.DsType;
import org.rrd4j.core.RrdDb;
import org.rrd4j.core.RrdDef;
import org.rrd4j.core.Sample;

import ru.r2cloud.R2Cloud;
import ru.r2cloud.uitl.Configuration;
import ru.r2cloud.uitl.Util;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricFilter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.ScheduledReporter;
import com.codahale.metrics.Timer;

public class RRD4JReporter extends ScheduledReporter {

	private static final Logger LOG = Logger.getLogger(R2Cloud.class.getName());
	private static final long STEP = 300; // secs

	private final File basepath;
	private final Map<String, RrdDb> dbPerMetric = new HashMap<String, RrdDb>();

	RRD4JReporter(Configuration config, MetricRegistry registry) {
		super(registry, "rrd4j-reporter", MetricFilter.ALL, TimeUnit.SECONDS, TimeUnit.MILLISECONDS);
		basepath = Util.initDirectory(config.getProperty("metrics.basepath.location"));
	}

	// never change the step. this will break previously created rrd files
	public void start() {
		start(STEP, TimeUnit.SECONDS);
	}

	@SuppressWarnings("rawtypes")
	@Override
	public void report(SortedMap<String, Gauge> gauges, SortedMap<String, Counter> counters, SortedMap<String, Histogram> histograms, SortedMap<String, Meter> meters, SortedMap<String, Timer> timers) {
		for (Entry<String, Gauge> cur : gauges.entrySet()) {
			update(getOrCreate(cur.getKey(), DsType.GAUGE), convertToDouble(cur.getValue().getValue()));
		}
		for (Entry<String, Counter> cur : counters.entrySet()) {
			update(getOrCreate(cur.getKey(), DsType.COUNTER), cur.getValue().getCount());
		}
	}

	private static double convertToDouble(Object value) {
		if (value instanceof Double) {
			return (Double) value;
		}
		if (value instanceof Long) {
			return ((Long) value).doubleValue();
		}
		if (value instanceof Integer) {
			return ((Integer) value).doubleValue();
		}
		throw new IllegalArgumentException("unsupported value type: " + value.getClass());
	}

	private static void update(RrdDb db, double value) {
		if (db == null) {
			return;
		}
		Sample sample;
		try {
			sample = db.createSample();
		} catch (IOException e) {
			LOG.log(Level.SEVERE, "unable to create sample", e);
			return;
		}
		System.out.println(value);
		sample.setValue("data", value);
		try {
			sample.update();
		} catch (IOException e) {
			LOG.log(Level.SEVERE, "unable to update", e);
		}
	}

	private RrdDb getOrCreate(String metricName, DsType type) {
		RrdDb result = dbPerMetric.get(metricName);
		if (result != null) {
			return result;
		}
		File path = new File(basepath, metricName + ".rrd");
		if (!path.exists()) {
			RrdDef rrdDef = new RrdDef(new File(basepath, metricName + ".rrd").getAbsolutePath(), System.currentTimeMillis() / 1000 - 1, STEP);
			rrdDef.setVersion(2);
			rrdDef.addDatasource("data", type, 2 * STEP, 0, Double.NaN);
			rrdDef.addArchive(AVERAGE, 0.5, 1, 600); // ~last 2 days. each point
														// is a 300 seconds
			rrdDef.addArchive(AVERAGE, 0.5, 24, 775); // ~last 2 months.
			rrdDef.addArchive(AVERAGE, 0.5, 288, 797); // ~last 2 years. each
														// point is a day
			try {
				result = new RrdDb(rrdDef);
			} catch (IOException e) {
				LOG.log(Level.SEVERE, "unable to create database: " + path.getAbsolutePath(), e);
				return null;
			}
		} else {
			try {
				result = new RrdDb(path.getAbsolutePath());
			} catch (IOException e) {
				LOG.log(Level.SEVERE, "unable to load database: " + path.getAbsolutePath(), e);
				return null;
			}
		}

		RrdDb old = dbPerMetric.put(metricName, result);
		if (old != null) {
			LOG.log(Level.SEVERE, "found duplicate rrddb: " + metricName);
			try {
				old.close();
			} catch (IOException e) {
				LOG.log(Level.SEVERE, "unable to close: " + old.getPath(), e);
			}
		}

		return result;
	}

	@Override
	public void close() {
		super.close();
		for (RrdDb cur : dbPerMetric.values()) {
			try {
				cur.close();
			} catch (IOException e) {
				LOG.log(Level.SEVERE, "unable to close: " + cur.getPath(), e);
			}
		}
	}

}
