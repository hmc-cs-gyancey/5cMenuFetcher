package io.yancey.menufetcher.fetchers;

import io.yancey.menufetcher.*;
import io.yancey.menufetcher.data.*;
import io.yancey.menufetcher.fetchers.dininghalls.*;

import java.io.*;
import java.net.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.regex.*;

import javax.script.*;

import org.jsoup.*;
import org.jsoup.nodes.*;
import org.jsoup.select.*;

public class SodexoMenuFetcher extends AbstractMenuFetcher {
	private static final List<String> mealNames = Arrays.asList("brk", "lun", "din");
	
	private final String sitename;
	private final int tcmId;
	private final String smgName;
	
	protected Map<String, Document> pageCache = new HashMap<>();
	protected Map<String, Object> smgCache = null;
	
	public SodexoMenuFetcher(String name, String id, String sitename, int tcmId, String smgName) {
		super(name, id);
		this.sitename = sitename;
		this.tcmId = tcmId;
		this.smgName = smgName;
	}
	
	private String getMenuUrl(LocalDate day) throws MenuNotAvailableException, MalformedMenuException {
		String menuUrl = null;
		Exception lastError = null;
		
		try {
			menuUrl = getMenuUrlFromPortal(day);
			if(!isCorrectWeek(menuUrl, day)) menuUrl = null;
		} catch(MenuNotAvailableException | MalformedMenuException e) {
			lastError = e;
			menuUrl = null;
		}
		
		if(menuUrl == null && DayOfWeek.MONDAY.adjustInto(day).equals(
				DayOfWeek.MONDAY.adjustInto(LocalDate.now()))) {
			System.err.println("Attempting to fetch menu from frontpage");
			try {
				menuUrl = getMenuUrlFromFrontpage(day);
				if(!isCorrectWeek(menuUrl, day)) menuUrl = null;
			} catch(MenuNotAvailableException | MalformedMenuException e) {
				if(lastError != null) lastError.printStackTrace();
				lastError = e;
				menuUrl = null;
			}
		}
		
		if(menuUrl == null) {
			System.err.println("Attempting to bruteforce magic menu number for "+id);
			try {
				menuUrl = getMenuUrlBruteforce(day);
				if(!isCorrectWeek(menuUrl, day)) menuUrl = null;
			} catch(MenuNotAvailableException e) {
				if(lastError != null) lastError.printStackTrace();
				lastError = e;
				menuUrl = null;
			}
		}
		
		if(menuUrl == null) {
			if(lastError != null) {
				throw new MenuNotAvailableException(id+": fetching menu failed", lastError);
			}
			throw new MenuNotAvailableException(id+": menu is not available yet");
		}
		
		return menuUrl;
	}
	
	private String getMenuUrlFromMenuId(int menuId) {
		return "https://" + sitename + ".sodexomyway.com/images/WeeklyMenu_tcm" +
				tcmId + "-" + menuId + ".htm";
	}
	
	private String getMenuUrlBruteforce(LocalDate day) {
		int[] menuIds = {109893,110702,121814,121817,121818,121819};
		for(int mId: menuIds) {
			String urlForId = getMenuUrlFromMenuId(mId);
			try {
				if(isCorrectWeek(urlForId, day)) return urlForId;
			} catch (MenuNotAvailableException e) {
				System.err.println("error fetching "+mId+" for "+id+":");
				e.printStackTrace();
			}
		}
		return null;
	}

	private boolean isCorrectWeek(String menuUrl, LocalDate day) throws MenuNotAvailableException {
		if(menuUrl == null) return false;
		Document menuPage = fetchMenuPage(menuUrl);
		String thisWeekString = ((LocalDate)DayOfWeek.MONDAY.adjustInto(day)).format(DateTimeFormatter.ofPattern("EEEE MMMM d, yyyy", Locale.ENGLISH));
		return menuPage.getElementsByClass("titlecell").text().contains(thisWeekString);
	}
	
	private String getFrontpageUrl() {
		return "https://" + sitename + ".sodexomyway.com/?forcedesktop=true";
	}
	
	private Pattern menuUrlPattern = null;
	private Pattern getMenuUrlPattern() {
		if(menuUrlPattern == null) {
			menuUrlPattern = Pattern.compile("/[Ii]mages/WeeklyMenu_tcm" + tcmId + "-([0-9]+)\\.htm");
		}
		return menuUrlPattern;
	}
	
	private String getMenuUrlFromFrontpage(LocalDate day)
			throws MalformedMenuException, MenuNotAvailableException {
		String frontpageString;
		try(Scanner sc = new Scanner(new URL(getFrontpageUrl()).openStream(), "UTF-8")) {
			sc.useDelimiter("\\A");
			frontpageString = sc.hasNext()? sc.next(): "";
		} catch (MalformedURLException e) {
			throw new MalformedMenuException("Invalid frontpage url", e);
		} catch (IOException e) {
			throw new MenuNotAvailableException("Error fetching frontpage",e);
		}
		Matcher menuUrlMatcher = getMenuUrlPattern().matcher(frontpageString);
		if(!menuUrlMatcher.find()) return null;
		return "https://" + sitename + ".sodexomyway.com" + menuUrlMatcher.group(0);
	}

	private String getPortalUrl() {
		return "https://" + sitename + ".sodexomyway.com/dining-choices/index.html?forcedesktop=true";
	}
	
	private static final Pattern datesPattern = Pattern.compile(
			"([0-9][0-9]?)/([0-9][0-9]?)/([0-9]+) - ([0-9][0-9]?)/([0-9][0-9]?)/([0-9]+)");
	public String getMenuUrlFromPortal(LocalDate day)
			throws MenuNotAvailableException, MalformedMenuException {
		if(!pageCache.containsKey(getPortalUrl())) {
			try {
				pageCache.put(getPortalUrl(), Jsoup.connect(getPortalUrl()).timeout(10*1000).get());
			} catch (IOException e) {
				throw new MenuNotAvailableException("Error fetching portal", e);
			}
		}
		Document portal = pageCache.get(getPortalUrl());
		Elements menus;
		try {
			menus = portal.getElementById("accordion_3543").getElementsByTag("ul").first().children();
		} catch(NullPointerException e) {
			throw new MenuNotAvailableException("Portal doesn't have any menus", e);
		}
		for(Element menuListing: menus) {
			String datesString = menuListing.child(0).ownText();
			Matcher dates = datesPattern.matcher(datesString);
			if(!dates.matches()) {
				//throw new MalformedMenuException("Invalid date range string: " + datesString);
				System.err.println("Invalid date string fetching "+id+": "+datesString);
				continue;
			}
			LocalDate startDate = LocalDate.of(
					Integer.parseInt(dates.group(3)),
					Integer.parseInt(dates.group(1)),
					Integer.parseInt(dates.group(2)));
			LocalDate endDate = LocalDate.of(
					Integer.parseInt(dates.group(6)),
					Integer.parseInt(dates.group(4)),
					Integer.parseInt(dates.group(5)));
			if(!day.isBefore(startDate) && !day.isAfter(endDate)) {
				return menuListing.child(0).absUrl("href") + "?forcedesktop=true";
			}
		}
		// requested date not found
		return null;
	}
	
	public Element getMenu(LocalDate day, Document menuPage) throws MenuNotAvailableException {
		return menuPage.getElementById(day.getDayOfWeek().getDisplayName(
				TextStyle.FULL_STANDALONE, Locale.ENGLISH).toLowerCase());
	}
	
	public String getPublicMenuUrl(String menuUrl, LocalDate day) {
		return menuUrl + "#" + day.getDayOfWeek().toString().toLowerCase();
	}
	
	public Document fetchMenuPage(String menuUrl) throws MenuNotAvailableException {
		if(!pageCache.containsKey(menuUrl)) {
			try(InputStream portalStream = new URL(menuUrl).openStream()) {
				pageCache.put(menuUrl, Jsoup.parse(portalStream, "Windows-1252", menuUrl));
			} catch (IOException e) {
				throw new MenuNotAvailableException("Error fetching menu",e);
			}
		}
		return pageCache.get(menuUrl);
	}
	
	@Override
	public Menu getMeals(LocalDate day) throws MenuNotAvailableException, MalformedMenuException {
		String menuUrl = null;
		try {
			menuUrl = getMenuUrl(day);
		} catch(MalformedMenuException e) {
			e.printStackTrace();
		} catch(MenuNotAvailableException e) {
			// expected; try to use smg
		}
		if(menuUrl == null) {
			if(smgName != null) {
				if(smgCache == null) fetchSmg();
				
				if(smgCache != null) {
					try {
						return getMenuFromSmg(day);
					} catch (MenuNotAvailableException e) {
						System.err.println("menu not available from smg: "+e);
					} catch (MalformedMenuException e) {
						System.err.println("error fetching menu for "+id+" from smg:");
						e.printStackTrace();
					}
				}
			}
			
			// no menu available for requested day
			return new Menu(name, id, getPublicMenuUrl(menuUrl, day), Collections.emptyList());
		}
		Document menuPage = fetchMenuPage(menuUrl);
		//String thisWeekString = ((LocalDate)DayOfWeek.MONDAY.adjustInto(day)).format(DateTimeFormatter.ofPattern("EEEE MMMM d, yyyy", Locale.ENGLISH));
		//if(!menuPage.getElementsByClass("titlecell").text().contains(thisWeekString)) {
		//	throw new MalformedMenuException("We fetched the menu for the wrong week");
		//}
		Element menu = getMenu(day, menuPage);
		List<Meal> meals = new ArrayList<>(3);
		for(String mealName: mealNames) {
			if(!menu.getElementsByClass(mealName).isEmpty()) {
				meals.add(createMeal(menu.getElementsByClass(mealName),
						day.getDayOfWeek().compareTo(DayOfWeek.FRIDAY) > 0));
			}
		}
		return new Menu(name, id, getPublicMenuUrl(menuUrl, day), meals);
	}
	
	@SuppressWarnings("unchecked")
	private Menu getMenuFromSmg(LocalDate day) throws MenuNotAvailableException, MalformedMenuException {
		Map<String, Map<String, Object>> menuData = (Map<String, Map<String, Object>>) smgCache.get("menu");
		Map<String, Map<String, String>> itemData = (Map<String, Map<String, String>>) smgCache.get("items");
		
		for(Map<String, Object> week: menuData.values()) {
			LocalDate startDate = LocalDate.parse((String)week.get("startDate"));
			LocalDate endDate = LocalDate.parse((String)week.get("endDate"));
			
			if(day.isBefore(startDate) || day.isAfter(endDate)) continue;
			
			Map<String, Map<String, Object>> mealsData = 
					((Map<String, Map<String, Map<String, Map<String, Map<String, Map<String, Object>>>>>>) week.get("menus"))
						.get("0").get("tabs").get(Integer.toString(startDate.until(day).getDays())).get("groups");

			List<Meal> meals = new ArrayList<>(3);
			for(Map<String, Object> mealData: mealsData.values()) {
				String mealName = (String) mealData.get("title");
				if(mealName == "Lunch" &&
						day.getDayOfWeek().equals(DayOfWeek.SATURDAY) || 
						day.getDayOfWeek().equals(DayOfWeek.SUNDAY)) {
					mealName = "Brunch";
				}
				Map<String, Map<String, Object>> stationsData = 
						(Map<String, Map<String, Object>>) mealData.get("category");
				List<Station> stations = new ArrayList<>();
				for(Map<String, Object> stationData: stationsData.values()) {
					String stationName = (String) stationData.get("title");
					Map<String, String> itemIds = (Map<String, String>) stationData.get("products");
					List<MenuItem> items = new ArrayList<>();
					for(String itemId: itemIds.values()) {
						items.add(new MenuItem(
								itemData.get(itemId).get("22"), 
								itemData.get(itemId).get("23"), 
								new HashSet<String>(Arrays.asList(
										itemData.get(itemId).get("30").split("\\s+")))));
					}
					stations.add(new Station(stationName, items));
				}
				meals.add(new Meal(stations, null, null, mealName, ""));
			}
			
			return new Menu(name, id, getPublicSmgUrl(), meals);
		}
		
		throw new MenuNotAvailableException("No menu in smg for "+day);
	}

	private void fetchSmg() {
		String smgUrl = getSmgUrl();
		String smgContents;
	    HttpURLConnection connection;
		try {
			connection = (HttpURLConnection) new URL(smgUrl).openConnection();
		} catch (IOException e) {
			System.err.println("Error fetching smg for "+id+":");
			e.printStackTrace();
			return;
		}
	    connection.setInstanceFollowRedirects(true);
		try(Scanner sc = new Scanner(new BufferedInputStream(connection.getInputStream()), "UTF-8")) {
			smgContents = sc.useDelimiter("\\A").next();
		} catch (IOException e) {
			System.err.println("Error fetching smg for "+id+":");
			e.printStackTrace();
			return;
		}
		try {
			smgCache = parseSmgJavascript(smgContents);
		} catch (ScriptException e) {
			System.err.println("Error evaluating javascript for "+id+"'s smg:");
			e.printStackTrace();
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> parseSmgJavascript(String smgContents) throws ScriptException {
        ScriptEngineManager factory = new ScriptEngineManager();
        ScriptEngine nashorn = factory.getEngineByName("nashorn");
        return (Map<String, Object>) nashorn.eval(
        		smgContents + "; var retData = {menu: menuData, items: aData}; retData");
	}

	private String getSmgUrl() {
		return "https://" + sitename + ".sodexomyway.com/smgmenu/json/" + smgName + "?forcedesktop=true";
	}

	private String getPublicSmgUrl() {
		return "https://" + sitename + ".sodexomyway.com/smgmenu/display/" + smgName + "?forcedesktop=true";
	}

	private Meal createMeal(Elements mealItems, boolean isWeekend) {
		String name = mealItems.remove(0).getElementsByClass("mealname").first().ownText();
		name = name.substring(0, 1) + name.substring(1).toLowerCase();
		if(name.equals("Lunch") && isWeekend) {
			name = "Brunch";
		}
		List<Station> stations = new ArrayList<>();
		ListIterator<Element> mealItemIter = mealItems.listIterator();
		while(mealItemIter.hasNext()) {
			stations.add(createStation(mealItemIter));
		}
		return new Meal(stations, null, null, name, "");
	}

	private Station createStation(ListIterator<Element> mealItemIter) {
		List<MenuItem> items = new ArrayList<>();
		String name = null;
		while(mealItemIter.hasNext()) {
			Element mealItem = mealItemIter.next();
			//System.out.println(mealItem);
			if(mealItem.getElementsByClass("station").isEmpty()) {
				if(name != null) {
					break;
				} else {
					continue;
				}
			}
			if(name == null) {
				name = mealItem.getElementsByClass("station").first().ownText().substring(1);
			}
			items.add(createMenuItem(mealItem));
		}
		return new Station(name, items);
	}

	private MenuItem createMenuItem(Element itemInfo) {
		itemInfo.getElementsByClass("station").remove();
		String name = itemInfo.text();
		Set<String> tags = new HashSet<>();
		for(Element tag: itemInfo.getElementsByClass("icon")) {
			tags.add(tag.attr("alt"));
		}
		return new MenuItem(name, "", tags);
	}

	public static void main(String[] args) throws MenuNotAvailableException, MalformedMenuException {
		System.out.println(new HochMenuFetcher().getMeals(LocalDate.of(2016, 10, 3)));
	}
}
