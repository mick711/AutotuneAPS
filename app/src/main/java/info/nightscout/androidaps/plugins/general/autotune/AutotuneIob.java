package info.nightscout.androidaps.plugins.general.autotune;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.annotations.NotNull;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import javax.inject.Inject;

import dagger.android.HasAndroidInjector;
import info.nightscout.androidaps.Constants;
import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.data.Intervals;
import info.nightscout.androidaps.data.Iob;
import info.nightscout.androidaps.data.IobTotal;
import info.nightscout.androidaps.data.NonOverlappingIntervals;
import info.nightscout.androidaps.data.OverlappingIntervals;
import info.nightscout.androidaps.data.Profile;
import info.nightscout.androidaps.data.ProfileIntervals;
import info.nightscout.androidaps.db.BgReading;
import info.nightscout.androidaps.db.CareportalEvent;
import info.nightscout.androidaps.db.ExtendedBolus;
import info.nightscout.androidaps.db.ProfileSwitch;
import info.nightscout.androidaps.db.Source;
import info.nightscout.androidaps.db.StaticInjector;
import info.nightscout.androidaps.db.TempTarget;
import info.nightscout.androidaps.db.TemporaryBasal;
import info.nightscout.androidaps.db.Treatment;
import info.nightscout.androidaps.interfaces.ActivePluginProvider;
import info.nightscout.androidaps.interfaces.BgSourceInterface;
import info.nightscout.androidaps.interfaces.ProfileFunction;
import info.nightscout.androidaps.interfaces.ProfileStore;
import info.nightscout.androidaps.interfaces.PumpInterface;
import info.nightscout.androidaps.plugins.general.autotune.data.NsTreatment;
import info.nightscout.androidaps.plugins.general.nsclient.NSUpload;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.AutosensResult;
import info.nightscout.androidaps.plugins.treatments.TreatmentsPlugin;
import info.nightscout.androidaps.utils.DateUtil;
import info.nightscout.androidaps.utils.Round;
import info.nightscout.androidaps.utils.T;
import info.nightscout.androidaps.utils.resources.ResourceHelper;
import info.nightscout.androidaps.utils.sharedPreferences.SP;
import io.reactivex.disposables.CompositeDisposable;


//Todo: Replace class below by a extended class of TreatmentsPlugin (see IobCobStaticCalculatorPlugin example in HistoryBrowser)
public class AutotuneIob {
    private final HasAndroidInjector injector;
    private static Logger log = LoggerFactory.getLogger(AutotunePlugin.class);
    @Inject ProfileFunction profileFunction;
    @Inject SP sp;
    @Inject ResourceHelper resourceHelper;
    @Inject ActivePluginProvider activePlugin;
    @Inject AutotunePlugin autotunePlugin;
    @Inject DateUtil dateUtil;
    @Inject TreatmentsPlugin treatmentsPlugin;
    @Inject NSUpload nsUpload;

    private CompositeDisposable disposable = new CompositeDisposable();

    private ArrayList<NsTreatment> nsTreatments = new ArrayList<NsTreatment>();
    private ArrayList<Treatment> treatments = new ArrayList<>();
    public ArrayList<Treatment> meals = new ArrayList<>();
    public List<BgReading> glucose;
    private Intervals<TemporaryBasal> tempBasals = new NonOverlappingIntervals<>();
    private Intervals<ExtendedBolus> extendedBoluses = new NonOverlappingIntervals<>();
    private Intervals<TempTarget> tempTargets = new OverlappingIntervals<>();
    private ProfileIntervals<ProfileSwitch> profiles = new ProfileIntervals<>();
    private long from;
    private long to;

    public AutotuneIob(
            //HasAndroidInjector injector,
            long from,
            long to
    ) {
        injector = StaticInjector.Companion.getInstance();
        //this.injector=injector;
        injector.androidInjector().inject(this);
        initializeData(from,to);
    }

    private long range() {
        double dia = Constants.defaultDIA;
        if (profileFunction.getProfile() != null)
            dia = profileFunction.getProfile().getDia();
        return (long) (60 * 60 * 1000L * dia);
    }

    private void initializeData(long from, long to) {
        this.from = from;
        this.to = to;
        nsTreatments.clear();
        initializeBgreadings(from, to);
        initializeTreatmentData(from-range(), to);
        initializeTempBasalData(from-range(), to);
        initializeExtendedBolusData(from-range(), to);
        initializeTempTargetData(from, to);
        initializeProfileSwitchData(from-range(), to);
        //NsTreatment is used to export all "ns-treatments" for cross execution of oref0-autotune on a virtual machine
        //it contains traitments, tempbasals and extendedbolus data (profileswitch data also included in ns-treatment files are not used by oref0-autotune)
        Collections.sort(nsTreatments, (o1, o2) -> (int) (o2.date  - o1.date) );
    }

    private void initializeBgreadings(long from, long to) {
        glucose = MainApp.getDbHelper().getBgreadingsDataFromTime(from, to, false);
    }

    private void initializeTreatmentData(long from, long to) {
        synchronized (treatments) {
            treatments.clear();
            treatments.addAll(treatmentsPlugin.getService().getTreatmentDataFromTime(from, to, false));
            meals.clear();
            for(int i = 0; i < treatments.size();i++) {
                Treatment tp =  treatments.get(i);
                nsTreatments.add(new NsTreatment(tp));
                if (tp.carbs > 0 && tp.date >= from)
                    meals.add(treatments.get(i));
            //only carbs after first BGReadings are taken into account in calculation of Autotune (I just have to check if I keep or not carbs after from and before first BGReadings)
                else if (tp.carbs > 0 && tp.date < from)
                    treatments.get(i).carbs = 0;
            }
        }
    }

    private void initializeTempBasalData(long from, long to) {
        synchronized (tempBasals) {
            List<TemporaryBasal> temp = MainApp.getDbHelper().getTemporaryBasalsDataFromTime(from, to, false);
            for (TemporaryBasal tb: temp ) {
                nsTreatments.add(new NsTreatment(tb));
            }
            tempBasals.reset().add(temp);
        }
    }

    private void initializeExtendedBolusData(long from, long to) {
        synchronized (extendedBoluses) {
            List<ExtendedBolus> temp = MainApp.getDbHelper().getExtendedBolusDataFromTime(from, to, false);
            for (ExtendedBolus eb: temp ) {
                nsTreatments.add(new NsTreatment(eb));
            }
            extendedBoluses.reset().add(temp);
        }
    }

    private void initializeTempTargetData(long from, long to) {
        synchronized (tempTargets) {
            tempTargets.reset().add(MainApp.getDbHelper().getTemptargetsDataFromTime(from, to, false));
        }
    }

    private void initializeProfileSwitchData(long from, long to) {
        synchronized (profiles) {
            profiles.reset().add(MainApp.getDbHelper().getProfileSwitchData(from, false));
        }
    }

    // on each loop glucose containts only one day BG Value
    public JSONArray glucosetoJSON()  {
        JSONArray glucoseJson = new JSONArray();
        Date now = new Date(System.currentTimeMillis());
        int utcOffset = (int) ((DateUtil.fromISODateString(DateUtil.toISOString(now,null,null)).getTime()  - DateUtil.fromISODateString(DateUtil.toISOString(now)).getTime()) / (60 * 1000));
        BgSourceInterface activeBgSource = activePlugin.getActiveBgSource();
        //String device = activeBgSource.getClass().getTypeName();
        try {
            for (BgReading bgreading:glucose ) {
                JSONObject bgjson = new JSONObject();
                bgjson.put("_id",bgreading._id);
                bgjson.put("device","AndroidAPS");
                bgjson.put("date",bgreading.date);
                bgjson.put("dateString", DateUtil.toISOString(bgreading.date));
                bgjson.put("sgv",bgreading.value);
                bgjson.put("direction",bgreading.direction);
                bgjson.put("type","sgv");
                bgjson.put("systime", DateUtil.toISOString(bgreading.date));
                bgjson.put("utcOffset", utcOffset);
                glucoseJson.put(bgjson);
            }
        } catch (JSONException e) {}
        return glucoseJson;
    }

    public JSONArray nsHistorytoJSON() {
        JSONArray json = new JSONArray();
        for (NsTreatment t: nsTreatments ) {
            if (t.isValid)
                json.put(t.toJson());
        }
        return json;
    }




    public IobTotal getCalculationToTimeTreatments(long time) {
        IobTotal total = new IobTotal(time);

        Profile profile = profileFunction.getProfile();
        if (profile == null)
            return total;

        PumpInterface pumpInterface = activePlugin.getActivePump();

        double dia = profile.getDia();

        synchronized (treatments) {
            for (int pos = 0; pos < treatments.size(); pos++) {
                Treatment t = treatments.get(pos);
                if (!t.isValid) continue;
                if (t.date > time) continue;
                Iob tIOB = t.iobCalc(time, dia);
                total.iob += tIOB.iobContrib;
                total.activity += tIOB.activityContrib;
                if (t.insulin > 0 && t.date > total.lastBolusTime)
                    total.lastBolusTime = t.date;
                if (!t.isSMB) {
                    // instead of dividing the DIA that only worked on the bilinear curves,
                    // multiply the time the treatment is seen active.
                    long timeSinceTreatment = time - t.date;
                    long snoozeTime = t.date + (long) (timeSinceTreatment * sp.getDouble(R.string.key_openapsama_bolussnooze_dia_divisor, 2.0));
                    Iob bIOB = t.iobCalc(snoozeTime, dia);
                    total.bolussnooze += bIOB.iobContrib;
                }
            }
        }

        if (!pumpInterface.isFakingTempsByExtendedBoluses())
            synchronized (extendedBoluses) {
                for (int pos = 0; pos < extendedBoluses.size(); pos++) {
                    ExtendedBolus e = extendedBoluses.get(pos);
                    if (e.date > time) continue;
                    IobTotal calc = e.iobCalc(time);
                    total.plus(calc);
                }
            }
        return total;
    }

    public List<Treatment> getTreatmentsFromHistory() {
        synchronized (treatments) {
            return new ArrayList<>(treatments);
        }
    }


    /**
     * Returns all Treatments after specified timestamp. Also returns invalid entries (required to
     * map "Fill Canulla" entries to history (and not to add double bolus for it)
     *
     * @param fromTimestamp
     * @return
     */
    public List<Treatment> getTreatmentsFromHistoryAfterTimestamp(long fromTimestamp) {
        List<Treatment> in5minback = new ArrayList<>();

        long time = System.currentTimeMillis();
        synchronized (treatments) {

            for (Treatment t : treatments) {
                if (t.date <= time && t.date >= fromTimestamp)
                    in5minback.add(t);
            }
            return in5minback;
        }
    }


    public List<Treatment> getCarbTreatments5MinBackFromHistory(long time) {
        List<Treatment> in5minback = new ArrayList<>();
        synchronized (treatments) {
            for (Treatment t : treatments) {
                if (!t.isValid)
                    continue;
                if (t.date <= time && t.date > time - 5 * 60 * 1000 && t.carbs > 0)
                    in5minback.add(t);
            }
            return in5minback;
        }
    }

    public long getLastBolusTime() {
        long now = System.currentTimeMillis();
        long last = 0;
        synchronized (treatments) {
            for (Treatment t : treatments) {
                if (!t.isValid)
                    continue;
                if (t.date > last && t.insulin > 0 && t.date <= now)
                    last = t.date;
            }
        }
        return last;
    }

    public long getLastBolusTime(boolean isSMB) {
        long now = System.currentTimeMillis();
        long last = 0;
        synchronized (treatments) {
            for (Treatment t : treatments) {
                if (!t.isValid)
                    continue;
                if (t.date > last && t.insulin > 0 && t.date <= now && isSMB == t.isSMB)
                    last = t.date;
            }
        }
        return last;
    }

    public boolean isInHistoryRealTempBasalInProgress() {
        return getRealTempBasalFromHistory(System.currentTimeMillis()) != null;
    }

    public TemporaryBasal getRealTempBasalFromHistory(long time) {
        synchronized (tempBasals) {
            return tempBasals.getValueByInterval(time);
        }
    }

    public boolean isTempBasalInProgress() {
        return getTempBasalFromHistory(System.currentTimeMillis()) != null;
    }

    public boolean isInHistoryExtendedBoluslInProgress() {
        return getExtendedBolusFromHistory(System.currentTimeMillis()) != null; //TODO:  crosscheck here
    }

    public IobTotal getCalculationToTimeTempBasals(long time) {
        return getCalculationToTimeTempBasals(time, false, 0);
    }

    public IobTotal getCalculationToTimeTempBasals(long time, boolean truncate, long truncateTime) {
        IobTotal total = new IobTotal(time);

        PumpInterface pumpInterface = activePlugin.getActivePump();

        synchronized (tempBasals) {
            for (Integer pos = 0; pos < tempBasals.size(); pos++) {
                TemporaryBasal t = tempBasals.get(pos);
                if (t.date > time) continue;
                IobTotal calc;
                Profile profile = profileFunction.getProfile(t.date);
                if (profile == null) continue;
                if (truncate && t.end() > truncateTime) {
                    TemporaryBasal dummyTemp = new TemporaryBasal(injector);
                    dummyTemp.copyFrom(t);
                    dummyTemp.cutEndTo(truncateTime);
                    calc = dummyTemp.iobCalc(time, profile);
                } else {
                    calc = t.iobCalc(time, profile);
                }
                //log.debug("BasalIOB " + new Date(time) + " >>> " + calc.basaliob);
                total.plus(calc);
            }
        }
        if (pumpInterface.isFakingTempsByExtendedBoluses()) {
            IobTotal totalExt = new IobTotal(time);
            synchronized (extendedBoluses) {
                for (int pos = 0; pos < extendedBoluses.size(); pos++) {
                    ExtendedBolus e = extendedBoluses.get(pos);
                    if (e.date > time) continue;
                    IobTotal calc;
                    Profile profile = profileFunction.getProfile(e.date);
                    if (profile == null) continue;
                    if (truncate && e.end() > truncateTime) {
                        ExtendedBolus dummyExt = new ExtendedBolus(injector);
                        dummyExt.copyFrom(e);
                        dummyExt.cutEndTo(truncateTime);
                        calc = dummyExt.iobCalc(time);
                    } else {
                        calc = e.iobCalc(time);
                    }
                    totalExt.plus(calc);
                }
            }
            // Convert to basal iob
            totalExt.basaliob = totalExt.iob;
            totalExt.iob = 0d;
            totalExt.netbasalinsulin = totalExt.extendedBolusInsulin;
            totalExt.hightempinsulin = totalExt.extendedBolusInsulin;
            total.plus(totalExt);
        }
        return total;
    }

    // for IOB calculations, use the average of the last 4 hours' basals to help convergence;
    // this helps since the basal this hour could be different from previous, especially if with autotune they start to diverge.
    // use the pumpbasalprofile to properly calculate IOB during periods where no temp basal is set
    public IobTotal getAbsoluteIOBTempBasals(long time) {
        IobTotal total = new IobTotal(time);
        Profile profile = profileFunction.getProfile(time);

        double running = profile.getBasal(time);
        running += profile.getBasal(time-1*60*60*1000);
        running += profile.getBasal(time-2*60*60*1000);
        running += profile.getBasal(time-3*60*60*1000);
        running = Round.roundTo(running/4,0.001);

        for (long i = time - range(); i < time; i += T.mins(5).msecs()) {
            TemporaryBasal runningTBR = getTempBasalFromHistory(i);
            if (runningTBR != null) {
                running = runningTBR.tempBasalConvertedToAbsolute(i, profile);
            }
            Treatment treatment = new Treatment(injector);
            treatment.date = i;
            treatment.insulin = running * 5.0 / 60.0; // 5 min chunk
            Iob iob = treatment.iobCalc(i, profile.getDia());
            total.iob += iob.iobContrib;
            total.activity += iob.activityContrib;
        }
        return total;
    }

    public IobTotal getCalculationToTimeTempBasals(long time, long truncateTime, AutosensResult lastAutosensResult, boolean exercise_mode, int half_basal_exercise_target, boolean isTempTarget) {
        IobTotal total = new IobTotal(time);

        PumpInterface pumpInterface = activePlugin.getActivePump();

        synchronized (tempBasals) {
            for (int pos = 0; pos < tempBasals.size(); pos++) {
                TemporaryBasal t = tempBasals.get(pos);
                if (t.date > time) continue;
                IobTotal calc;
                Profile profile = profileFunction.getProfile(t.date);
                if (profile == null) continue;
                if (t.end() > truncateTime) {
                    TemporaryBasal dummyTemp = new TemporaryBasal(injector);
                    dummyTemp.copyFrom(t);
                    dummyTemp.cutEndTo(truncateTime);
                    calc = dummyTemp.iobCalc(time, profile, lastAutosensResult, exercise_mode, half_basal_exercise_target, isTempTarget);
                } else {
                    calc = t.iobCalc(time, profile, lastAutosensResult, exercise_mode, half_basal_exercise_target, isTempTarget);
                }
                //log.debug("BasalIOB " + new Date(time) + " >>> " + calc.basaliob);
                total.plus(calc);
            }
        }
        if (pumpInterface.isFakingTempsByExtendedBoluses()) {
            IobTotal totalExt = new IobTotal(time);
            synchronized (extendedBoluses) {
                for (int pos = 0; pos < extendedBoluses.size(); pos++) {
                    ExtendedBolus e = extendedBoluses.get(pos);
                    if (e.date > time) continue;
                    IobTotal calc;
                    Profile profile = profileFunction.getProfile(e.date);
                    if (profile == null) continue;
                    if (e.end() > truncateTime) {
                        ExtendedBolus dummyExt = new ExtendedBolus(injector);
                        dummyExt.copyFrom(e);
                        dummyExt.cutEndTo(truncateTime);
                        calc = dummyExt.iobCalc(time, profile, lastAutosensResult, exercise_mode, half_basal_exercise_target, isTempTarget);
                    } else {
                        calc = e.iobCalc(time, profile, lastAutosensResult, exercise_mode, half_basal_exercise_target, isTempTarget);
                    }
                    totalExt.plus(calc);
                }
            }
            // Convert to basal iob
            totalExt.basaliob = totalExt.iob;
            totalExt.iob = 0d;
            totalExt.netbasalinsulin = totalExt.extendedBolusInsulin;
            totalExt.hightempinsulin = totalExt.extendedBolusInsulin;
            total.plus(totalExt);
        }
        return total;
    }

    @Nullable
    public TemporaryBasal getTempBasalFromHistory(long time) {
        TemporaryBasal tb = getRealTempBasalFromHistory(time);
        if (tb != null)
            return tb;
        ExtendedBolus eb = getExtendedBolusFromHistory(time);
        if (eb != null && activePlugin.getActivePump().isFakingTempsByExtendedBoluses())
            return new TemporaryBasal(eb);
        return null;
    }

    public ExtendedBolus getExtendedBolusFromHistory(long time) {
        synchronized (extendedBoluses) {
            return extendedBoluses.getValueByInterval(time);
        }
    }

    @NonNull
    public Intervals<ExtendedBolus> getExtendedBolusesFromHistory() {
        synchronized (extendedBoluses) {
            return new NonOverlappingIntervals<>(extendedBoluses);
        }
    }

    @NonNull
    public NonOverlappingIntervals<TemporaryBasal> getTemporaryBasalsFromHistory() {
        synchronized (tempBasals) {
            return new NonOverlappingIntervals<>(tempBasals);
        }
    }

    public long oldestDataAvailable() {
        long oldestTime = System.currentTimeMillis();
        synchronized (tempBasals) {
            if (tempBasals.size() > 0)
                oldestTime = Math.min(oldestTime, tempBasals.get(0).date);
        }
        synchronized (extendedBoluses) {
            if (extendedBoluses.size() > 0)
                oldestTime = Math.min(oldestTime, extendedBoluses.get(0).date);
        }
        synchronized (treatments) {
            if (treatments.size() > 0)
                oldestTime = Math.min(oldestTime, treatments.get(treatments.size() - 1).date);
        }
        oldestTime -= 15 * 60 * 1000L; // allow 15 min before
        return oldestTime;
    }

    @Nullable
    public TempTarget getTempTargetFromHistory(long time) {
        synchronized (tempTargets) {
            return tempTargets.getValueByInterval(time);
        }
    }

    public Intervals<TempTarget> getTempTargetsFromHistory() {
        synchronized (tempTargets) {
            return new OverlappingIntervals<>(tempTargets);
        }
    }

    @Nullable
    public ProfileSwitch getProfileSwitchFromHistory(long time) {
        synchronized (profiles) {
            return (ProfileSwitch) profiles.getValueToTime(time);
        }
    }

    public ProfileIntervals<ProfileSwitch> getProfileSwitchesFromHistory() {
        synchronized (profiles) {
            return new ProfileIntervals<>(profiles);
        }
    }

    public void addToHistoryProfileSwitch(ProfileSwitch profileSwitch) {
        //log.debug("Adding new TemporaryBasal record" + profileSwitch.log());
        //rxBus.send(new EventDismissNotification(Notification.PROFILE_SWITCH_MISSING));
        MainApp.getDbHelper().createOrUpdate(profileSwitch);
        nsUpload.uploadProfileSwitch(profileSwitch);
    }

    public void doProfileSwitch(@NotNull final ProfileStore profileStore, @NotNull final String profileName, final int duration, final int percentage, final int timeShift, final long date) {
        ProfileSwitch profileSwitch = profileFunction.prepareProfileSwitch(profileStore, profileName, duration, percentage, timeShift, date);
        addToHistoryProfileSwitch(profileSwitch);
        if (percentage == 90 && duration == 10)
            sp.putBoolean(R.string.key_objectiveuseprofileswitch, true);
    }

    public void doProfileSwitch(final int duration, final int percentage, final int timeShift) {
        ProfileSwitch profileSwitch = getProfileSwitchFromHistory(System.currentTimeMillis());
        if (profileSwitch != null) {
            profileSwitch = new ProfileSwitch(injector);
            profileSwitch.date = System.currentTimeMillis();
            profileSwitch.source = Source.USER;
            profileSwitch.profileName = profileFunction.getProfileName(System.currentTimeMillis(), false, false);
            profileSwitch.profileJson = profileFunction.getProfile().getData().toString();
            profileSwitch.profilePlugin = activePlugin.getActiveProfileInterface().getClass().getName();
            profileSwitch.durationInMinutes = duration;
            profileSwitch.isCPP = percentage != 100 || timeShift != 0;
            profileSwitch.timeshift = timeShift;
            profileSwitch.percentage = percentage;
            addToHistoryProfileSwitch(profileSwitch);
        } else {
            //log.error(LTag.PROFILE, "No profile switch exists");
        }
    }
}