package com.mapzen.erasermap.view

import android.content.Intent
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.preference.PreferenceManager
import android.support.v4.view.MenuItemCompat
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.Display
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.Toast
import com.mapzen.erasermap.BuildConfig
import com.mapzen.erasermap.CrashReportService
import com.mapzen.erasermap.EraserMapApplication
import com.mapzen.erasermap.R
import com.mapzen.erasermap.model.AppSettings
import com.mapzen.erasermap.model.RouteManager
import com.mapzen.erasermap.model.TileHttpHandler
import com.mapzen.erasermap.presenter.MainPresenter
import com.mapzen.pelias.BoundingBox
import com.mapzen.pelias.Pelias
import com.mapzen.pelias.SavedSearch
import com.mapzen.pelias.SimpleFeature
import com.mapzen.pelias.gson.Feature
import com.mapzen.pelias.gson.Geometry
import com.mapzen.pelias.gson.Properties
import com.mapzen.pelias.gson.Result
import com.mapzen.pelias.widget.AutoCompleteAdapter
import com.mapzen.pelias.widget.AutoCompleteListView
import com.mapzen.pelias.widget.PeliasSearchView
import com.mapzen.tangram.LngLat
import com.mapzen.tangram.MapController
import com.mapzen.tangram.MapData
import com.mapzen.tangram.MapView
import com.mapzen.tangram.Tangram
import com.mapzen.valhalla.Route
import com.mapzen.valhalla.RouteCallback
import com.mapzen.valhalla.Router
import retrofit.Callback
import retrofit.RetrofitError
import retrofit.client.Response
import java.util.ArrayList
import javax.inject.Inject

public class MainActivity : AppCompatActivity(), MainViewController, RouteCallback,
        SearchResultsView.OnSearchResultSelectedListener {

    public val requestCodeSearchResults: Int = 0x01

    var savedSearch: SavedSearch? = null
        @Inject set
    var presenter: MainPresenter? = null
        @Inject set
    var crashReportService: CrashReportService? = null
        @Inject set
    var routeManager: RouteManager? = null
        @Inject set
    var settings: AppSettings? = null
        @Inject set
    var tileHttpHandler: TileHttpHandler? = null
        @Inject set

    var app: EraserMapApplication? = null
    var mapController : MapController? = null
    var autoCompleteAdapter: AutoCompleteAdapter? = null
    var optionsMenu: Menu? = null
    var routeLine: MapData? = null
    var findMe: MapData? = null
    var searchResults: MapData? = null

    var findMeButton: ImageButton? = null
    var routePreviewView: RoutePreviewView? = null
    var routeModeView: RouteModeView? = null
    var reverseButton: ImageButton? = null
    var viewListButton: Button? = null
    var startNavigationButton: Button? = null
    var byCar: RadioButton? = null
    var byBike: RadioButton? = null
    var byFoot: RadioButton? = null

    override public fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as EraserMapApplication
        app?.component()?.inject(this)
        initCrashReportService()
        setContentView(R.layout.activity_main)
        initViews()
        presenter?.mainViewController = this
        initMapController()
        initAutoCompleteAdapter()
        initFindMeButton()
        initReverseButton()
        presenter?.onCreate()
        presenter?.onRestoreViewState()
    }

    private fun initViews() {
        findMeButton = findViewById(R.id.find_me) as ImageButton?
        routePreviewView = findViewById(R.id.route_preview) as RoutePreviewView?
        routeModeView = findViewById(R.id.route_mode) as RouteModeView?
        reverseButton = findViewById(R.id.route_reverse) as ImageButton?
        viewListButton = findViewById(R.id.view_list) as Button?
        startNavigationButton = findViewById(R.id.start_navigation) as Button?
        byCar = findViewById(R.id.by_car) as RadioButton?
        byBike = findViewById(R.id.by_bike) as RadioButton?
        byFoot = findViewById(R.id.by_foot) as RadioButton?
    }

    override public fun onStart() {
        super.onStart()
        savedSearch?.deserialize(PreferenceManager.getDefaultSharedPreferences(this)
                .getString(SavedSearch.TAG, null))
    }

    override public fun onResume() {
        super.onResume()
        presenter?.onResume()
    }

    override public fun onPause() {
        super.onPause()
        presenter?.onPause()
    }

    override public fun onStop() {
        super.onStop()
        PreferenceManager.getDefaultSharedPreferences(this)
                .edit()
                .putString(SavedSearch.TAG, savedSearch?.serialize())
                .commit()
    }

    override public fun onDestroy() {
        super.onDestroy()
        saveCurrentSearchTerm()
        clearRouteLine()
    }

    private fun initMapController() {
        val mapView = findViewById(R.id.map) as MapView
        mapController = MapController(this, mapView, "style/eraser-map.yaml")
        mapController?.setLongPressListener(View.OnGenericMotionListener {
            view, motionEvent -> reverseGeolocate(motionEvent) })
        mapController?.setHttpHandler(tileHttpHandler)
    }

    private fun initAutoCompleteAdapter() {
        autoCompleteAdapter = AutoCompleteAdapter(this, R.layout.list_item_auto_complete)
        autoCompleteAdapter?.setRecentSearchIconResourceId(R.drawable.ic_recent)
        autoCompleteAdapter?.setAutoCompleteIconResourceId(R.drawable.ic_pin_outline)
    }

    private fun initFindMeButton() {
        findMe = MapData("find_me")
        Tangram.addDataSource(findMe);
        findMeButton?.visibility = View.VISIBLE
        findMeButton?.setOnClickListener({ presenter?.onFindMeButtonClick() })
    }

    private fun initCrashReportService() {
        crashReportService?.initAndStartSession(this)
    }

    override fun centerMapOnLocation(location: Location, zoom: Float) {
        mapController?.setMapPosition(location.longitude, location.latitude)
        mapController?.mapZoom = zoom
        showCurrentLocation(location)
    }

    override fun showCurrentLocation(location: Location) {
        val currentLocation = LngLat(location.longitude, location.latitude)
        val properties = com.mapzen.tangram.Properties()
        properties.add("type", "point");

        findMe?.clear()
        findMe?.addPoint(properties, currentLocation)
        findMe?.update();
    }

    override fun setMapTilt(radians: Float) {
        mapController?.mapTilt = radians
    }

    override fun setMapRotation(radians: Float) {
        mapController?.mapRotation = radians
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        optionsMenu = menu

        MenuItemCompat.setOnActionExpandListener(menu.findItem(R.id.action_search),
                SearchOnActionExpandListener())

        val searchView = menu.findItem(R.id.action_search).actionView
        val listView = findViewById(R.id.auto_complete) as AutoCompleteListView
        val emptyView = findViewById(android.R.id.empty)

        if (searchView is PeliasSearchView) {
            listView.adapter = autoCompleteAdapter
            val pelias = Pelias.getPelias()
            pelias.setLocationProvider(presenter?.getPeliasLocationProvider())
            pelias.setApiKey(BuildConfig.PELIAS_API_KEY)
            searchView.setAutoCompleteListView(listView)
            searchView.setSavedSearch(savedSearch)
            searchView.setPelias(Pelias.getPelias())
            searchView.setCallback(PeliasCallback())
            searchView.setOnSubmitListener({ presenter?.onQuerySubmit() })
            listView.emptyView = emptyView
            restoreCurrentSearchTerm()
        }

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val id = item.itemId
        when (id) {
            R.id.action_settings -> { onActionSettings(); return true }
            R.id.action_search -> { onActionSearch(); return true }
            R.id.action_clear -> { onActionClear(); return true }
            R.id.action_view_all -> { onActionViewAll(); return true }
        }

        return super.onOptionsItemSelected(item)
    }

    private fun onActionSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun onActionSearch() {
    }

    private fun onActionClear() {
        savedSearch?.clear()
        autoCompleteAdapter?.clear()
        autoCompleteAdapter?.notifyDataSetChanged()
    }

    private fun onActionViewAll() {
        presenter?.onViewAllSearchResults()
    }

    override fun showAllSearchResults(features: List<Feature>) {
        val simpleFeatures: ArrayList<SimpleFeature> = ArrayList()
        for (feature in features) {
            simpleFeatures.add(SimpleFeature.fromFeature(feature))
        }

        val menuItem = optionsMenu?.findItem(R.id.action_search)
        val actionView = menuItem?.actionView as PeliasSearchView
        setBoundingBox()
        val intent = Intent(this, SearchResultsListActivity::class.java)
        intent.putParcelableArrayListExtra("features", simpleFeatures)
        intent.putExtra("query", actionView.query.toString())
        startActivityForResult(intent, requestCodeSearchResults)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode > 0) {
            (findViewById(R.id.search_results) as SearchResultsView).setCurrentItem(resultCode - 1)
        }
    }

    private fun saveCurrentSearchTerm() {
        val menuItem = optionsMenu?.findItem(R.id.action_search)
        val actionView = menuItem?.actionView
        val isExpanded = menuItem?.isActionViewExpanded ?: false
        if (actionView is PeliasSearchView && isExpanded) {
            presenter?.currentSearchTerm = actionView.query.toString()
        }
    }

    private fun restoreCurrentSearchTerm() {
        val menuItem = optionsMenu?.findItem(R.id.action_search)
        val actionView = menuItem?.actionView as PeliasSearchView
        val term = presenter?.currentSearchTerm
        if (term != null) {
            menuItem?.expandActionView()
            actionView.setQuery(term, false)
            if (findViewById(R.id.search_results).visibility == View.VISIBLE) {
                actionView.clearFocus()
                showActionViewAll()
            }
            presenter?.currentSearchTerm = null
        }
    }

    inner class PeliasCallback : Callback<Result> {
        private val TAG: String = "PeliasCallback"

        override fun success(result: Result?, response: Response?) {
            presenter?.onSearchResultsAvailable(result)
        }

        override fun failure(error: RetrofitError?) {
            hideProgress()
            Log.e(TAG, "Error fetching search results: " + error?.getMessage())
            Toast.makeText(this@MainActivity, "Error fetching search results",
                    Toast.LENGTH_LONG).show()
        }
    }

    inner class ReversePeliasCallback : Callback<Result> {
        private val TAG: String = "ReversePeliasCallback"

        override fun success(result: Result?, response: Response?) {
            presenter?.onReverseGeocodeResultsAvailable(result)
        }

        override fun failure(error: RetrofitError?) {
            hideProgress()
            Log.e(TAG, "Error Reverse Geolocating: " + error?.getMessage())
        }
    }

    inner class SearchOnActionExpandListener : MenuItemCompat.OnActionExpandListener {
        override fun onMenuItemActionExpand(item: MenuItem?): Boolean {
            setBoundingBox()
            presenter?.onExpandSearchView()
            return true
        }

        override fun onMenuItemActionCollapse(item: MenuItem?): Boolean {
            presenter?.onCollapseSearchView()
            return true
        }
    }

    override fun showSearchResults(features: List<Feature>) {
        showSearchResultsPager(features)
        addSearchResultsToMap(features)
    }

    private fun showSearchResultsPager(features: List<Feature>) {
        val pager = findViewById(R.id.search_results) as SearchResultsView
        pager.setAdapter(SearchResultsAdapter(this, features))
        pager.visibility = View.VISIBLE
        pager.onSearchResultsSelectedListener = this
    }

    override fun showReverseGeocodeFeature(features: List<Feature>) {
        val pager = findViewById(R.id.search_results) as SearchResultsView
        pager.setAdapter(SearchResultsAdapter(this, features.subList(0, 1)))
        pager.visibility = View.VISIBLE
        pager.onSearchResultsSelectedListener = this
    }

    private fun addSearchResultsToMap(features: List<Feature>) {
        centerOnCurrentFeature(features)

        if (searchResults == null) {
            searchResults = MapData("search")
            Tangram.addDataSource(searchResults);
        }

        searchResults?.clear()
        for (feature in features) {
            val simpleFeature = SimpleFeature.fromFeature(feature)
            val lngLat = LngLat(simpleFeature.lon, simpleFeature.lat)
            val properties = com.mapzen.tangram.Properties()
            properties.add("type", "point");

            searchResults?.addPoint(properties, lngLat)
            searchResults?.update();
        }
    }

    override fun centerOnCurrentFeature(features: List<Feature>) {
        Handler().postDelayed({
            if(features.size() > 0) {
                val pager = findViewById(R.id.search_results) as SearchResultsView
                val position = pager.getCurrentItem()
                val feature = SimpleFeature.fromFeature(features.get(position))
                mapController?.setMapPosition(feature.lon, feature.lat)
                mapController?.mapZoom = MainPresenter.DEFAULT_ZOOM
            }
        }, 100)
    }

    public fun reverseGeolocate(event: MotionEvent) : Boolean {
        val pelias = Pelias.getPelias()
        pelias.setLocationProvider(presenter?.getPeliasLocationProvider())
        var coords  = mapController?.coordinatesAtScreenPosition(
                event.rawX.toDouble(), event.rawY.toDouble())
        presenter?.currentFeature = getGenericLocationFeature(coords?.latitude as Double,
                coords?.longitude as Double)
        pelias.reverse(coords?.latitude.toString(), coords?.longitude.toString(),
                ReversePeliasCallback())
        return true
    }

    override fun hideSearchResults() {
        hideSearchResultsPager()
        searchResults?.clear()
    }

    private fun hideSearchResultsPager() {
        (findViewById(R.id.search_results) as SearchResultsView).visibility = View.GONE
    }

    override fun showProgress() {
        findViewById(R.id.progress).visibility = View.VISIBLE
    }

    override fun hideProgress() {
        findViewById(R.id.progress).visibility = View.GONE
    }

    override fun showOverflowMenu() {
        optionsMenu?.setGroupVisible(R.id.menu_overflow, true)
    }

    override fun hideOverflowMenu() {
        optionsMenu?.setGroupVisible(R.id.menu_overflow, false)
    }

    override fun onSearchResultSelected(position: Int) {
        presenter?.onSearchResultSelected(position)
    }

    override fun showActionViewAll() {
        optionsMenu?.findItem(R.id.action_view_all)?.setVisible(true)
    }

    override fun hideActionViewAll() {
        optionsMenu?.findItem(R.id.action_view_all)?.setVisible(false)
    }

    override fun collapseSearchView() {
        optionsMenu?.findItem(R.id.action_search)?.collapseActionView()
    }

    override fun showRoutePreview(location: Location, feature: Feature) {
        routeManager?.origin = location
        routeManager?.destination = feature

        if (location.hasBearing()) {
            routeManager?.bearing = location.bearing
        } else {
            routeManager?.bearing = null
        }

        routePreviewView?.destination = SimpleFeature.fromFeature(feature)
        route()
    }

    override fun clearRouteLine() {
        routeLine?.clear()
    }

    override fun success(route: Route) {
        routeManager?.route = route
        routePreviewView?.route = route
        runOnUiThread ({
            if (routeModeView?.visibility != View.VISIBLE) {
                supportActionBar?.hide()
                routePreviewView?.visibility = View.VISIBLE
            }
        })
        updateRoutePreview()
        drawRouteLine(route)
        hideProgress()
    }

    private fun drawRouteLine(route: Route) {
        val properties = com.mapzen.tangram.Properties()
        properties.add("type", "line");
        val geometry: ArrayList<Location>? = route.getGeometry()
        val mapGeometry: ArrayList<LngLat> = ArrayList()
        if (geometry is ArrayList<Location>) {
            for (location in geometry) {
                mapGeometry.add(LngLat(location.longitude, location.latitude))
            }
        }

        if (routeLine == null) {
            routeLine = MapData("route")
            Tangram.addDataSource(routeLine);
        }

        routeLine?.clear()
        routeLine?.addLine(properties, mapGeometry)
        routeLine?.update();
    }

    override fun failure(statusCode: Int) {
        hideProgress()
        Toast.makeText(this@MainActivity, "No route found", Toast.LENGTH_LONG).show()
    }

    override fun hideRoutePreview() {
        if((findViewById(R.id.route_mode) as RouteModeView).visibility != View.VISIBLE) {
            supportActionBar?.show()
            routeManager?.reverse = false
            findViewById(R.id.route_preview).visibility = View.GONE
        }
    }

    private fun route() {
        showProgress()
        routeManager?.fetchRoute(this)
    }

    private fun updateRoutePreview() {
        byCar?.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked) {
                routeManager?.type = Router.Type.DRIVING
                route()
            }
        }

        byBike?.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked) {
                routeManager?.type = Router.Type.BIKING
                route()
            }
        }

        byFoot?.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked) {
                routeManager?.type = Router.Type.WALKING
                route()
            }
        }
    }

    private fun reverse() {
        routeManager?.toggleReverse()
        routePreviewView?.reverse = routeManager?.reverse ?: false
        route()
    }

    private fun initReverseButton() {
        reverseButton?.setOnClickListener({ reverse() })
        viewListButton?.setOnClickListener({ presenter?.onClickViewList() })
        startNavigationButton?.setOnClickListener({ presenter?.onClickStartNavigation() })
    }

    override fun onBackPressed() {
        presenter?.onBackPressed()
    }

    override fun shutDown() {
        finish()
    }

    override fun showDirectionList() {
        val instructionStrings = ArrayList<String>()
        val instructionType = ArrayList<Int>()
        val instructionDistance = ArrayList<Int>()
        val instructions = routeManager?.route?.getRouteInstructions()
        if (instructions != null) {
            for(instruction in instructions) {
                val humanInstruction = instruction.getHumanTurnInstruction()
                if (humanInstruction is String) {
                    instructionStrings.add(humanInstruction)
                }
                instructionType.add(instruction.turnInstruction)
                instructionDistance.add(instruction.distance)
            }
        }

        val simpleFeature = SimpleFeature.fromFeature(routeManager?.destination)
        val intent = Intent(this, InstructionListActivity::class.java)
        intent.putExtra("instruction_strings", instructionStrings)
        intent.putExtra("instruction_types", instructionType)
        intent.putExtra("instruction_distances", instructionDistance)
        intent.putExtra("destination", simpleFeature.toString())
        intent.putExtra("reverse", routeManager?.reverse)
        startActivityForResult(intent, requestCodeSearchResults)
    }

    override fun startRoutingMode(feature: Feature) {
        showRoutingMode(feature)
        routeModeView?.startRoute(feature, routeManager?.route)
    }

    override fun resumeRoutingMode(feature: Feature) {
        showRoutingMode(feature)
        routeModeView?.resumeRoute(feature, routeManager?.route)
    }

    private fun showRoutingMode(feature: Feature) {
        hideFindMe()
        supportActionBar?.hide()
        routeManager?.destination = feature
        routeManager?.reverse = false
        routePreviewView?.visibility = View.GONE
        routeModeView?.mainPresenter = presenter
        routeModeView?.mapController = mapController
        presenter?.routeViewController = routeModeView
        routeModeView?.voiceNavigationController = VoiceNavigationController(this)
    }

    override fun hideRoutingMode() {
        initFindMeButton()
        presenter?.routingEnabled = false
        routeModeView?.visibility = View.GONE
        val location = routeManager?.origin
        val feature = routeManager?.destination
        if (location is Location && feature is Feature) {
            showRoutePreview(location, feature)
        }
        supportActionBar?.hide()
        routeModeView?.route = null
        routeModeView?.hideRouteIcon()
    }

    private fun setBoundingBox() {
        val menuItem = optionsMenu?.findItem(R.id.action_search)
        var mdisp : Display = getWindowManager().getDefaultDisplay()
        var minLatLon = mapController?.coordinatesAtScreenPosition(0.0, mdisp.height.toDouble())
        var maxLatLon = mapController?.coordinatesAtScreenPosition(mdisp.width.toDouble(), 0.0)
        var bbox: BoundingBox = BoundingBox(minLatLon?.latitude as Double,
                minLatLon?.longitude as Double,
                maxLatLon?.latitude as Double,
                maxLatLon?.longitude as Double)
        (menuItem?.actionView  as PeliasSearchView).setBoundingBox(bbox)
    }

    private fun getGenericLocationFeature(lat: Double, lon: Double) : Feature {
        var nameLength: Int = 6
        val feature = Feature()
        val properties = Properties()
        if(lat.toString().length() > nameLength && lon.toString().length() > nameLength + 1) {
            properties.label = lat.toString().substring(0, nameLength) + "," + lon.toString()
                    .substring(0, nameLength + 1)
        }
        feature.properties = properties
        val geometry = Geometry()
        val coordinates = ArrayList<Double>()
        coordinates.add(lon)
        coordinates.add(lat)
        geometry.coordinates = coordinates
        feature.geometry = geometry
        return feature
    }

    private fun hideFindMe() {
        findMe?.clear()
        findMe = null
        findViewById(R.id.find_me).visibility = View.GONE
    }
}
