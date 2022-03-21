package info.nightscout.androidaps.plugins.general.autotune.data

import dagger.android.HasAndroidInjector
import info.nightscout.androidaps.core.R
import info.nightscout.androidaps.data.LocalInsulin
import info.nightscout.androidaps.data.ProfileSealed
import info.nightscout.androidaps.data.PureProfile
import info.nightscout.androidaps.database.data.Block
import info.nightscout.androidaps.extensions.blockValueBySeconds
import info.nightscout.androidaps.extensions.pureProfileFromJson
import info.nightscout.androidaps.interfaces.*
import info.nightscout.androidaps.plugins.bus.RxBus
import info.nightscout.androidaps.utils.DateUtil
import info.nightscout.androidaps.utils.Round
import info.nightscout.androidaps.utils.T
import info.nightscout.androidaps.utils.resources.ResourceHelper
import info.nightscout.shared.SafeParse
import info.nightscout.shared.sharedPreferences.SP
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.*
import javax.inject.Inject

class ATProfile(profile: Profile?, var localInsulin: LocalInsulin, val injector: HasAndroidInjector) {

    var profile: ProfileSealed
    var profilename: String? = profile?.profileName
    var basal = DoubleArray(24)
    var basalUntuned = IntArray(24)
    var ic = 0.0
    var isf = 0.0
    var dia = 0.0
    var peak = 0
    var isValid: Boolean = false
    var from: Long = 0

    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var sp: SP
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var config: Config
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var rh: ResourceHelper

    fun getProfile(): PureProfile {
        return profile.convertToNonCustomizedProfile(dateUtil)
    }

    fun updateProfile() {
        profile = ProfileSealed.Pure(data!!)
    }

    val icSize: Int
        get() = profile.getIcsValues().size
    val isfSize: Int
        get() = profile.getIsfsMgdlValues().size
    val avgISF: Double
        get() = if (profile.getIsfsMgdlValues().size == 1) profile.getIsfsMgdlValues().get(0).value else Round.roundTo(averageProfileValue(profile.getIsfsMgdlValues()), 0.01)
    val avgIC: Double
        get() = if (profile.getIcsValues().size == 1) profile.getIcsValues().get(0).value else Round.roundTo(averageProfileValue(profile.getIcsValues()), 0.01)

    //Export json string with oref0 format used for autotune
    fun profiletoOrefJSON(): String {
        // Create a json profile with oref0 format
        // Include min_5m_carbimpact, insulin type, single value for carb_ratio and isf
        var jsonString = ""
        val json = JSONObject()
        //val json = profile.toPureNsJson(dateUtil)
        //jsonString = json.toString(2).replace("\\/", "/")

        val basalIncrement = 60
        val insulinInterface: Insulin = activePlugin.activeInsulin
        try {
            json.put("name", profilename)
            json.put("min_5m_carbimpact", sp.getDouble("openapsama_min_5m_carbimpact", 3.0))
            json.put("dia", dia)
            if (insulinInterface.id === Insulin.InsulinType.OREF_ULTRA_RAPID_ACTING) json.put(
                "curve",
                "ultra-rapid"
            ) else if (insulinInterface.id === Insulin.InsulinType.OREF_RAPID_ACTING) json.put("curve", "rapid-acting") else if (insulinInterface.id === Insulin.InsulinType.OREF_LYUMJEV) {
                json.put("curve", "ultra-rapid")
                json.put("useCustomPeakTime", true)
                json.put("insulinPeakTime", 45)
            } else if (insulinInterface.id === Insulin.InsulinType.OREF_FREE_PEAK) {
                val peaktime: Int = sp.getInt(rh.gs(R.string.key_insulin_oref_peak), 75)
                json.put("curve", if (peaktime > 50) "rapid-acting" else "ultra-rapid")
                json.put("useCustomPeakTime", true)
                json.put("insulinPeakTime", peaktime)
            }
            val basals = JSONArray()
            for (h in 0..23) {
                val secondfrommidnight = h * 60 * 60
                var time: String
                time = (if (h < 10) "0$h" else h).toString() + ":00:00"
                //basals.put(new JSONObject().put("start", time).put("minutes", h * basalIncrement).put("rate", getProfileBasal(h)));
                basals.put(JSONObject().put("start", time).put("minutes", h * basalIncrement).put("rate", profile.getBasalTimeFromMidnight(secondfrommidnight)))
            }
            json.put("basalprofile", basals)
            val isfvalue = Profile.fromMgdlToUnits(avgISF, profile.units)
            json.put(
                "isfProfile",
                JSONObject().put(
                    "sensitivities",
                    JSONArray().put(JSONObject().put("i", 0).put("start", "00:00:00").put("sensitivity", isfvalue).put("offset", 0).put("x", 0).put("endoffset", 1440))
                )
            )
            // json.put("carbratio", new JSONArray().put(new JSONObject().put("time", "00:00").put("timeAsSeconds", 0).put("value", previousResult.optDouble("carb_ratio", 0d))));
            json.put("carb_ratio", avgIC)
            json.put("autosens_max", SafeParse.stringToDouble(sp.getString(R.string.key_openapsama_autosens_max, "1.2")))
            json.put("autosens_min", SafeParse.stringToDouble(sp.getString(R.string.key_openapsama_autosens_min, "0.7")))
            json.put("units", profileFunction.getUnits().asText)
            json.put("timezone", TimeZone.getDefault().id)
            jsonString = json.toString(2).replace("\\/", "/")
        } catch (e: JSONException) {}

        return jsonString
    }

    //json profile
    val data: PureProfile?
        get() {
        val json: JSONObject = profile.toPureNsJson(dateUtil)
        try {
            json.put("dia", dia)
            json.put(
                "sens", JSONArray().put(
                    JSONObject().put("time", "00:00").put(
                        "timeAsSeconds", 0
                    ).put(
                        "value", Profile.fromMgdlToUnits(
                            isf,
                            profile.units
                        )
                    )
                )
            )
            json.put("carbratio", JSONArray().put(JSONObject().put("time", "00:00").put("timeAsSeconds", 0).put("value", ic)))
            val basals = JSONArray()
            for (h in 0..23) {
                val secondfrommidnight = h * 60 * 60
                var time: String
                time = (if (h < 10) "0$h" else h).toString() + ":00"
                //basals.put(new JSONObject().put("start", time).put("minutes", h * basalIncrement).put("rate", getProfileBasal(h)));
                basals.put(JSONObject().put("time", time).put("timeAsSeconds", secondfrommidnight).put("value", basal[h]))
            }
            json.put("basal", basals)
        } catch (e: JSONException) {
        }
        return pureProfileFromJson(json, dateUtil, profile.units.asText)
    }

    fun getBasal(timestamp: Long):Double = basal[(Profile.secondsFromMidnight(timestamp)/T.hours(1).secs()).toInt()]

    val profileStore: ProfileStore?
        get() {
            var profileStore: ProfileStore? = null
            val json = JSONObject()
            val store = JSONObject()
            try {
                store.put(rh.gs(R.string.autotune_tunedprofile_name), profile.toPureNsJson(dateUtil))
                json.put("defaultProfile", rh.gs(R.string.autotune_tunedprofile_name))
                json.put("store", store)
                json.put("startDate", dateUtil.toISOAsUTC(dateUtil.now()))
                profileStore = ProfileStore(injector, json, dateUtil)
            } catch (e: JSONException) {
            }
            return profileStore
        }

    companion object {

        fun averageProfileValue(pf: Array<Profile.ProfileValue>?): Double {
            var avgValue = 0.0
            val secondPerDay = 24 * 60 * 60
            if (pf == null) return avgValue
            for (i in pf.indices) {
                avgValue += pf[i].value * ((if (i == pf.size - 1) secondPerDay else pf[i + 1].timeAsSeconds) - pf[i].timeAsSeconds)
            }
            avgValue /= secondPerDay.toDouble()
            return avgValue
        }
    }

   init {
        injector.androidInjector().inject(this)
        this.profile = profile as ProfileSealed
        isValid = profile.isValid
        if (isValid) {
            //initialize tuned value with current profile values
            for(h in 0..23) { basal[h] = Round.roundTo(profile.basalBlocks.blockValueBySeconds(T.hours(h.toLong()).secs().toInt(), 1.0, 0), 0.001) }
            ic = avgIC
            isf = avgISF
        }
       dia = localInsulin.dia
       peak = localInsulin.peak
    }
}