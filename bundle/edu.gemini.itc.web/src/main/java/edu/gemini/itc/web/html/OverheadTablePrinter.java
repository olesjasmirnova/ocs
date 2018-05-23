package edu.gemini.itc.web.html;

import edu.gemini.itc.base.ImagingResult;
import edu.gemini.itc.base.Result;
import edu.gemini.itc.shared.*;
import edu.gemini.shared.util.immutable.ImList;
import edu.gemini.spModel.config2.Config;
import edu.gemini.spModel.obs.plannedtime.OffsetOverheadCalculator;
import edu.gemini.spModel.obs.plannedtime.PlannedTime;
import edu.gemini.spModel.obs.plannedtime.PlannedTimeCalculator;
import edu.gemini.spModel.time.TimeAmountFormatter;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.StringJoiner;


public class OverheadTablePrinter {
    private final ItcParameters p;
    private final Config[] config;
    private final double readoutTimePerCoadd;
    private final PlannedTime pta;
    private final ItcSpectroscopyResult r;
    private final String instrumentName;
    private final int numOfExposures;

    private PlannedTime.Step s;
    private Map<PlannedTime.Category, ImList<PlannedTime.CategorizedTime>> m;
    private ImList<PlannedTime.CategorizedTime> cts;

    public interface PrinterWithOverhead {
        Config[] createInstConfig(int numberExposures);
        PlannedTime.ItcOverheadProvider getInst();
        double getReadoutTimePerCoadd(); // this should return "0" for instruments with no coadds
    }

    public OverheadTablePrinter(PrinterWithOverhead printer, final ItcParameters params, final double readoutTimePerCoadd, Result result, ItcSpectroscopyResult sResult) {

        final ObservationDetails obs = result.observation();
        final CalculationMethod calcMethod = obs.calculationMethod();


        if (calcMethod instanceof ImagingInt) {
            this.numOfExposures = (int)(((ImagingResult) result).is2nCalc().numberSourceExposures() * obs.sourceFraction());
        } else if (calcMethod instanceof ImagingS2N) {
            this.numOfExposures = ((ImagingS2N) calcMethod).exposures();
        } else if (calcMethod instanceof SpectroscopyS2N) {
            this.numOfExposures = ((SpectroscopyS2N) calcMethod).exposures();
        } else {
            this.numOfExposures = 1;
        }

        this.r = sResult;
        this.p = params;
        this.config = printer.createInstConfig(this.numOfExposures);
        this.readoutTimePerCoadd = readoutTimePerCoadd;
        this.pta = PlannedTimeCalculator.instance.calc(this.config, printer.getInst());
        this.instrumentName = (String) config[0].getItemValue(ConfigCreator.InstInstrumentKey);
    }

    // instruments with no coadds, case of spectroscopy for instruments with IFU
    public OverheadTablePrinter(PrinterWithOverhead printer, ItcParameters p, Result r, ItcSpectroscopyResult sr) {
        this(printer, p, 0, r, sr);
    }

    // instruments with coadds, case of imaging, or case of spectroscopy for instruments with no IFU
    public OverheadTablePrinter(PrinterWithOverhead printer, ItcParameters p,  double readoutTimePerCoadd, Result r) {
        this(printer, p, readoutTimePerCoadd, r, null);
    }

    // instruments with no coadds, case of imaging, or case of spectroscopy for instruments with no IFU
    public OverheadTablePrinter(PrinterWithOverhead printer, ItcParameters p, Result r) {
        this(printer, p, 0, r, null);
    }


    public int getGsaoiLgsReacqNum() {
        int gsaoiLgsReacqNum = 0;

        for (int i = 0; i < config.length; i++) {
            s = pta.steps.get(i);
            m = s.times.groupTimes();
            cts = m.get(PlannedTime.Category.CONFIG_CHANGE);

            if (cts != null) {
                for (PlannedTime.CategorizedTime lct : cts) {
                    if (lct.detail.equals("LGS Reacquisition")) {
                        gsaoiLgsReacqNum++;
                    }
                }
            }
        }
        return gsaoiLgsReacqNum;
    }


    /**
     *  get number of reacquisitions
     */
    public int getNumReacq() {
        int numReacq = 0;
        if (p.observation().calculationMethod() instanceof Spectroscopy) {
            numReacq = pta.numReacq(config[0]);
            // for IFU spectroscopy recentering is needed only for faint targets (with SNR in individual images < 5)
            if (isIFU()) {
                if (r.maxSingleSNRatio() > 5) {
                    numReacq = 0;
                }
            }
        }
        return numReacq;
    }

    public boolean isIFU() {
        String fpu = null;
        if (config[0].containsItem(ConfigCreator.FPUKey)) {
            fpu = config[0].getItemValue(ConfigCreator.FPUKey).toString();
        }
        if ((instrumentName.contains("GMOS") && fpu.contains("IFU"))
                || instrumentName.equals("NIFS")) {
            return true;
        }
        return false;
    }

    public Map<Double, Integer> getOffsetsByOverhead() {
        Map<Double, Integer> offsetsByOverhead = new HashMap<>();

        for (int i = 0; i < config.length; i++) {
            s = pta.steps.get(i);
            m = s.times.groupTimes();
            cts = m.get(PlannedTime.Category.CONFIG_CHANGE);

            if (cts != null) {
                for (PlannedTime.CategorizedTime lct : cts) {
                    Double time = lct.time / 1000.0;
                    if (lct.detail.equals(OffsetOverheadCalculator.DETAIL) && lct.time != 0) {
                        offsetsByOverhead.put(time, offsetsByOverhead.getOrDefault(time, 0) + 1);
                    }
                }
            }
        }
        return offsetsByOverhead;
    }


    public String printOverheadTable() {
        StringBuilder buf = new StringBuilder("<html><body>");
        buf.append("<table><tr><th>Observation Overheads</th></tr>");

        if (!ConfigCreator.getOverheadTableWarning().equals("")) {
            buf.append("</table>");
            buf.append("<div>");
            buf.append("<p>").append(ConfigCreator.getOverheadTableWarning()).append("</p>");
            buf.append("</body></html>");
            return buf.toString();
        }


        // print setup overheads, counting one full setup per every two hours of science
        String setupStr = "";
        int numAcq = pta.numAcq();
        if (numAcq == 1) {
            setupStr = String.format("%.1f s", pta.setup.time / 1000.0);
        } else if (numAcq > 1) {
            setupStr = String.format("%d visits x %.1f s", numAcq, pta.setup.time / 1000.0);
        }
        buf.append("<tr>");
        buf.append("<td>").append("Setup ").append("</td>");
        buf.append("<td align=\"right\"> ").append(setupStr).append("</td>");
        buf.append("</tr>");

        /**  print reacquisition overheads:
         *    - as target "Recentering" on the slit for all instruments but GSAOI
         *    - as "LGS Reacquisition" for GSAOI after large unguided sky offsets
         */
        int numReacq = getNumReacq();
        int numLgsReacq = getGsaoiLgsReacqNum();
        String  reacqStr = "";

        if (numReacq > 0) {
            reacqStr = String.format("%d x %.1f s", numReacq, pta.setup.reacquisitionTime / 1000.0);
        }
        if (numLgsReacq > 0) {
            reacqStr = String.format("%d x %.1f s", numLgsReacq, pta.setup.reacquisitionTime / 1000.0);
        }

        if (!reacqStr.equals("")) {
            buf.append("<tr>");
            buf.append("<td>").append("LGS reacquisition ").append("</td>");
            buf.append("<td align=\"right\"> ").append(reacqStr).append("</td>");
            //   buf.append("<td align=\"right\"> ").append("&ensp; required when returning to science target after sky offset").append("</td>");
            buf.append("</tr>");
        }

        // print offset overheads
        if (!getOffsetsByOverhead().isEmpty()) {
            StringJoiner joiner = new StringJoiner(" + ");
            for (Map.Entry<Double, Integer> entry: getOffsetsByOverhead().entrySet()) {
                joiner.add(String.format("%d x %.1f s", entry.getValue(), entry.getKey()));
            }
            String offsetStr = joiner.toString();

            buf.append("<tr>");
            buf.append("<td>").append("Telescope offset ").append("</td>");
            buf.append("<td align=\"right\"> ").append(offsetStr).append("</td>");
            buf.append("<td align=\"right\"> ").append("&ensp; assuming " + ConfigCreator.getOffsetString()).append("</td>");
            buf.append("</tr>");
        }

        // print the rest of categories (for which the times are the same for all steps, so using just first step)
        String secStr = "";
        s = pta.steps.get(0);
        m = s.times.groupTimes();

        for (PlannedTime.Category c : PlannedTime.Category.values()) {
            cts = m.get(c);
            if (cts == null) continue;
            PlannedTime.CategorizedTime ct = cts.max(Comparator.naturalOrder());
            int coadds = p.observation().calculationMethod().coaddsOrElse(1);
            String category = (ct.detail == null) ? ct.category.display : ct.detail;

            buf.append("<tr>");
            buf.append("<td>").append(category).append("</td>");

            if (category.equals("Exposure") && (coadds !=1 )) {
                secStr = String.format("%d exp x (%d coadds x %.1f s)", numOfExposures, coadds, ct.time/1000.0/coadds);
            } else if ((category.equals("Readout") && (coadds!=1) && (readoutTimePerCoadd != 0) )) {
                secStr = String.format("%d exp x (%d coadds x %.1f s)", numOfExposures, coadds, readoutTimePerCoadd, ct.time/1000.0);
            } else {
                secStr = String.format("%d exp x %.1f s", numOfExposures, ct.time/1000.0);
            }

            buf.append("<td align=\"right\"> ").append(secStr).append("</td>");
            buf.append("</tr>");
        }

        long totalTime = pta.totalTimeWithReacq(numReacq);
        buf.append("<tr><td><b>Total time</b></td><td align=\"right\"><b>").append(String.format("%s", TimeAmountFormatter.getDescriptiveFormat(totalTime))).append("</b></td></tr>");
        buf.append("</table>");

        buf.append("</body></html>");
        return buf.toString();
    }
}
