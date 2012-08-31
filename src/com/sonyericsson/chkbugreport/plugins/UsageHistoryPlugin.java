package com.sonyericsson.chkbugreport.plugins;

import com.sonyericsson.chkbugreport.Chapter;
import com.sonyericsson.chkbugreport.Plugin;
import com.sonyericsson.chkbugreport.Report;
import com.sonyericsson.chkbugreport.Section;
import com.sonyericsson.chkbugreport.SectionInputStream;
import com.sonyericsson.chkbugreport.Util;
import com.sonyericsson.chkbugreport.plugins.PackageInfoPlugin.PackageInfo;
import com.sonyericsson.chkbugreport.plugins.logs.event.ActivityManagerStatsGenerator;
import com.sonyericsson.chkbugreport.plugins.logs.event.ComponentStat;
import com.sonyericsson.chkbugreport.plugins.logs.event.EventLogPlugin;
import com.sonyericsson.chkbugreport.util.TableGen;
import com.sonyericsson.chkbugreport.util.XMLNode;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Vector;

public class UsageHistoryPlugin extends Plugin {

    private static final String TAG = "[UsageHistoryPlugin]";
    private XMLNode mData;
    private HashMap<String, PackageStat> mStats;

    @Override
    public int getPrio() {
        return 92;
    }

    @Override
    public void load(Report br) {
        mData = null;
        Section s = br.findSection(Section.USAGE_HISTORY);
        if (s == null) {
            br.printErr(3, TAG + "Cannot find section: " + Section.USAGE_HISTORY);
            return;
        }
        mData = XMLNode.parse(new SectionInputStream(s));
        HashMap<String, PackageStat> stats = new HashMap<String, UsageHistoryPlugin.PackageStat>();
        if (!mData.getName().equals("usage-history")) {
            br.printErr(4, TAG + "Cannot parse section " + Section.USAGE_HISTORY + ": root tag invalid: " + mData.getName());
            return;
        }
        for (XMLNode pkg : mData) {
            if (pkg.getName() == null) continue; // ignore text
            if (!"pkg".equals(pkg.getName())) {
                br.printErr(4, TAG + "Cannot parse section " + Section.USAGE_HISTORY + ": package tag invalid: " + pkg.getName());
                return;
            }
            PackageStat pkgStat = new PackageStat();
            pkgStat.pkg = pkg.getAttr("name");
            for (XMLNode act : pkg) {
                if (act.getName() == null) continue; // ignore text
                if (!"comp".equals(act.getName())) {
                    br.printErr(4, TAG + "Cannot parse section " + Section.USAGE_HISTORY + ": component tag invalid: " + act.getName());
                    return;
                }
                ActivityStat actStat = new ActivityStat();
                actStat.pkg = pkgStat.pkg;
                actStat.cls = act.getAttr("name");
                actStat.lrt = Long.parseLong(act.getAttr("lrt"));
                pkgStat.lrt = Math.max(pkgStat.lrt, actStat.lrt);
                pkgStat.activities.add(actStat);
            }
            stats.put(pkgStat.pkg, pkgStat);
        }
        mStats = stats;
    }

    @Override
    public void generate(Report br) {
        if (mStats == null) return;

        EventLogPlugin plugin = (EventLogPlugin) br.getPlugin("EventLogPlugin");
        ActivityManagerStatsGenerator amStats = plugin.getActivityMStats();
        if (amStats == null || amStats.isEmpty()) {
            br.printErr(3, TAG + "Cannot find AM statistics");
            return;
        }
        PackageInfoPlugin pkgPlugin = (PackageInfoPlugin) br.getPlugin("PackageInfoPlugin");
        if (pkgPlugin.isEmpty()) {
            br.printErr(3, TAG + "Cannot find package list");
            return;
        }

        Chapter ch = new Chapter(br, "Usage history");
        br.addChapter(ch);
        long lastTs = plugin.getLastTs();
        long duration = lastTs - plugin.getFirstTs();
        long now = System.currentTimeMillis();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSZ");
        ch.addLine("<div class=\"hint\">(Note: the age is calculated using as reference the time when chkbugreport was calculated)</div>");
        TableGen tg = new TableGen(ch, TableGen.FLAG_SORT);
        tg.setCSVOutput(br, "usage_history_vs_log");
        tg.setTableName(br, "usage_history_vs_log");
        tg.addColumn("Package", null, "pkg varchar", TableGen.FLAG_NONE);
        tg.addColumn("Type", null, "type varchar", TableGen.FLAG_NONE);
        tg.addColumn("Last used", null, "last_used varchar", TableGen.FLAG_NONE);
        tg.addColumn("Age", null, "age int", TableGen.FLAG_ALIGN_RIGHT);
        tg.addColumn("Services started", null, "services_started int", TableGen.FLAG_ALIGN_RIGHT);
        tg.addColumn("Max created time(ms)", null, "created_time_max_ms int", TableGen.FLAG_ALIGN_RIGHT);
        tg.addColumn("Max created time(%)", null, "created_time_max_p int", TableGen.FLAG_ALIGN_RIGHT);

        tg.begin();
        for (PackageInfo pkg : pkgPlugin.getPackages()) {
            tg.addData(pkg.getName());
            tg.addData((pkg.getFlags() & 1) == 1 ? "System" : "Installed");

            PackageStat stat = mStats.get(pkg.getName());
            if (stat == null) {
                tg.addData("");
                tg.addData("");
            } else {
                long age = (now - stat.lrt) / 1000 / 60 / 60 / 24;
                tg.addData(sdf.format(new Date(stat.lrt)));
                tg.addData(Long.toString(age));
            }

            Vector<ComponentStat> srvStats = amStats.getServiceStatsOfPackage(pkg.getName());
            if (srvStats == null || srvStats.isEmpty()) {
                tg.addData("");
                tg.addData("");
                tg.addData("");
            } else {
                long max = 0;
                int count = 0;
                for (ComponentStat cs : srvStats) {
                    max = Math.max(max, cs.maxCreatedTime);
                    count += cs.createCount;
                }
                tg.addData(count);
                tg.addData(Util.shadeValue(max));
                tg.addData(max * 100 / duration);
            }
        }
        tg.end();
    }

    public static class ActivityStat {
        String pkg;
        String cls;
        long lrt;
    }

    public static class PackageStat {
        String pkg;
        long lrt;
        Vector<ActivityStat> activities = new Vector<ActivityStat>();
    }

}