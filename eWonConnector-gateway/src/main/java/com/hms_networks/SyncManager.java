package com.hms_networks;

import java.io.IOException;
import java.util.*;
import com.inductiveautomation.ignition.common.sqltags.model.types.*;
import com.inductiveautomation.ignition.common.sqltags.parser.BasicTagPath;
import org.apache.commons.lang3.StringUtils;
import com.hms_networks.comm.CommunicationManger;
import com.hms_networks.config.EwonConnectorSettings;
import com.hms_networks.config.EwonSyncData;
import com.hms_networks.data.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.inductiveautomation.ignition.common.FormatUtil;
import com.inductiveautomation.ignition.common.TypeUtilities;
import com.inductiveautomation.ignition.common.model.values.Quality;
import com.inductiveautomation.ignition.common.sqltags.BasicTagValue;
import com.inductiveautomation.ignition.common.sqltags.history.InterpolationMode;
import com.inductiveautomation.ignition.common.sqltags.model.TagPath;
import com.inductiveautomation.ignition.common.sqltags.parser.TagPathParser;
import com.inductiveautomation.ignition.common.util.TimeUnits;
import com.inductiveautomation.ignition.gateway.history.HistoricalTagValue;
import com.inductiveautomation.ignition.gateway.history.PackedHistoricalTagValue;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.sqltags.model.BasicScanclassHistorySet;
import com.inductiveautomation.ignition.gateway.sqltags.simple.SimpleTagProvider;
import com.inductiveautomation.ignition.gateway.sqltags.simple.WriteHandler;

/**
 * Manager for synchronization and configuration of Ewon Connector
 */
public class SyncManager {
   /**
    * Tag path for last synchronization time
    */
   private static final String STATUS_LASTSYNCTIME = "_Status/LastSyncTime";

   /**
    * Tag path for last synchronization duration in ms
    */
   private static final String STATUS_LASTSYNCDURATION = "_Status/LastSyncDurationMS";

   /**
    * Tag path for last historical data synchronization time
    */
   private static final String STATUS_LAST_H_SYNCTIME = "_Status/LastHistoricalSyncTime";

   /**
    * Tag path for last historical data synchronization identifier
    */
   private static final String STATUS_LASTSYNCID = "_Status/LastHistoricalTransaction";

   /**
    * Tag path for reset sync tag
    */
   private static final String STATUS_RESETSYNC = "_Status/ResetSync";

   /**
    * Tag path for force sync tag
    */
   private static final String STATUS_FORCE_HSYNC = "_Status/ForceSync";

   /**
    * Tag path for successful synchronization counter
    */
   private static final String STATUS_SUCCESSCOUNT = "_Status/SuccessfulSyncCount";

   /**
    * Tag path for failed synchronization counter
    */
   private static final String STATUS_FAILURECOUNT = "_Status/FailedSyncCount";

   /**
    * Tag path for historical points processed counter
    */
   private static final String STATUS_HIST_POINT_CNT = "_Status/HistoricalPointsProcessed";

   /**
    * Hash set of registered Ewon Connector tags
    */
   private static HashSet<String> registeredTags = new HashSet<String>();

   /**
    * Hash set of registered Ewon Connector Ewons
    */
   private static HashSet<String> registeredEwons = new HashSet<>();

   /**
    * Hash set of Ewons to be updated in realtime
    */
   private static HashSet<String> realtimeEwons = new HashSet<>();

   /**
    * Current gateway context
    */
   GatewayContext gatewayContext;

   /**
    * Log handler
    */
   Logger logger = LoggerFactory.getLogger("Ewon.SyncManager");

   /**
    * Current tag provider
    */
   SimpleTagProvider provider;

   /**
    * Current communication manager
    */
   CommunicationManger comm;

   /**
    * Tag history store information
    */
   String tagHistoryStore;

   /**
    * Cache of last synchronization information, key = ewonId, value = lastSynchroDate
    */
   Map<Integer, Date> lastSyncCache = new HashMap<>();

   /**
    * Boolean if tag history is enabled
    */
   boolean historyEnabled = false;

   /**
    * Boolean if tag name sanitization is enabled
    */
   boolean replaceUnderscore = false;

   /**
    * Boolean if force all tags realtime is enabled
    */
   boolean readAllTagsRealtime = false;

   /**
    * Ewon synchronization data
    */
   EwonSyncData syncData = null;

   /**
    * Counter for successful synchronizations
    */
   long successCount = 0;

   /**
    * Counter for failed synchronizations
    */
   long failureCount = 0;

   /**
    * Counter for number of historical data points
    */
   long histPoints = 0;

   /**
    * Main constructor for creation of sync managers
    * @param context gateway context
    * @param provider tag provider
    */
   public SyncManager(GatewayContext context, SimpleTagProvider provider) {
      this.gatewayContext = context;
      this.provider = provider;
   }

   /**
    * Handle startup and registration of Ewon Connector module
    * @param settings settings to use
    */
   public void startup(EwonConnectorSettings settings) {
      logger.info("Starting Ewon sync manager.");

      // Create and configure communication manager
      comm = new CommunicationManger();
      comm.setAuthInfo(settings.getAuthInfo());

      // Store tag name sanitization information
      replaceUnderscore = settings.isReplaceUnderscore();

      // Store read all tags in realtime configuration information
      readAllTagsRealtime = settings.isForceLive();

      // Store tag history configuration information
      historyEnabled = settings.isHistoryEnabled();
      tagHistoryStore = settings.getHistoryProvider();

      // Verify integrity of tag history storage information if tag history enabled
      if (historyEnabled && StringUtils.isBlank(tagHistoryStore)) {
         logger.warn(
               "History sync is enable, but no history provider has been specified for storage. No data will be stored.");
      }

      // Load and store synchronization data, and create it if necessary
      syncData = gatewayContext.getPersistenceInterface().find(EwonSyncData.META, 1L);
      if (syncData == null) {
         logger.info("Ewon sync data not found, initializing.");
         syncData = gatewayContext.getLocalPersistenceInterface().createNew(EwonSyncData.META);
         syncData.setLong(EwonSyncData.Id, 1L);
         gatewayContext.getLocalPersistenceInterface().save(syncData);
      }

      // Load and register polling interval configuration information
      long pollRateMS = TimeUnits.toMillis(settings.getPollRate().doubleValue(), TimeUnits.MIN);
      logger.debug("Configuring polling for {} ms", pollRateMS);
      if (pollRateMS > 0) {
         gatewayContext.getExecutionManager().register("ewon", "syncpoll", this::run,
               (int) pollRateMS);
      }

      // Load and register realtime polling interval configuration information
      long livePollRateMS =
              TimeUnits.toMillis(settings.getLivePollRate().doubleValue(), TimeUnits.SEC);
      logger.debug("Configuring live polling for {} ms", livePollRateMS);
      if (pollRateMS > 0) {
         gatewayContext.getExecutionManager().register("ewon", "synclive", this::runLive,
                 (int) livePollRateMS);
      }

      // Configure Ewon Connector status/statistics tags
      provider.configureTag(buildTagPath(STATUS_LASTSYNCTIME), DataType.DateTime, TagType.Custom);
      provider.configureTag(buildTagPath(STATUS_LAST_H_SYNCTIME), DataType.DateTime,
            TagType.Custom);
      provider.configureTag(buildTagPath(STATUS_LASTSYNCID), DataType.Int4, TagType.Custom);
      provider.configureTag(buildTagPath(STATUS_RESETSYNC), DataType.Boolean, TagType.Custom);
      provider.configureTag(buildTagPath(STATUS_FORCE_HSYNC), DataType.Boolean, TagType.Custom);

      // Update values of Ewon Connector status/statistics tags
      provider.updateValue(buildTagPath(STATUS_LAST_H_SYNCTIME), syncData.getLastLocalSync(),
            DataQuality.GOOD_DATA);
      provider.updateValue(buildTagPath(STATUS_LASTSYNCID), syncData.getTransactionId(),
            DataQuality.GOOD_DATA);
      provider.updateValue(buildTagPath(STATUS_RESETSYNC), Boolean.FALSE, DataQuality.GOOD_DATA);
      provider.updateValue(buildTagPath(STATUS_FORCE_HSYNC), Boolean.FALSE, DataQuality.GOOD_DATA);
      provider.updateValue(buildTagPath(STATUS_SUCCESSCOUNT), successCount, DataQuality.GOOD_DATA);
      provider.updateValue(buildTagPath(STATUS_FAILURECOUNT), failureCount, DataQuality.GOOD_DATA);
      provider.updateValue(buildTagPath(STATUS_HIST_POINT_CNT), histPoints, DataQuality.GOOD_DATA);

      // Create write handler to enable write to tag for resetting synchronization information
      provider.registerWriteHandler(buildTagPath(STATUS_RESETSYNC), new WriteHandler() {
         @Override
         public Quality write(TagPath path, Object val) {
            logger.info("Resetting historical sync transaction id.");
            syncData.setTransactionId(0L);
            syncData.setLastLocalSync(new Date(0));
            successCount = 0;
            failureCount = 0;
            histPoints = 0;
            saveSyncData();
            updateStatusTags();
            return DataQuality.GOOD_DATA;
         }
      });

      // Create write handler to enable write to tag for forcing historical data synchronization
      provider.registerWriteHandler(buildTagPath(STATUS_FORCE_HSYNC), new WriteHandler() {
         @Override
         public Quality write(TagPath path, Object val) {
            logger.info("Forcing historical sync");
            gatewayContext.getExecutionManager().executeOnce(() -> executeSync());
            return DataQuality.GOOD_DATA;
         }
      });
   }

   /**
    * Handle shutdown and unregister of Ewon Connector module
    */
   public void shutdown() {
      logger.info("Shutting down Ewon sync manager.");
      gatewayContext.getExecutionManager().unRegister("ewon", "syncpoll");
   }

   /**
    * Get and return if tag history is enabled
    * @return true/false if tag history is enabled
    */
   protected boolean isHistoryEnabled() {
      return historyEnabled && !StringUtils.isBlank(tagHistoryStore);
   }

   /**
    * Perform a full synchronization/poll of Ewon data and tags
    */
   protected void run() {
      try {
         long start = System.currentTimeMillis();
         logger.debug("Starting poll...");
         executeSync();
         logger.debug("Poll completed in {}", FormatUtil.formatDurationSince(start));
      } catch (Exception e) {
         logger.error("Error polling Ewon data.", e);
      }
   }

   /**
    * Perform a synchronization of realtime tags
    */
   protected void runLive() {
      updateLive();
   }

   protected void updateLive() {
      HashMap<String, ArrayList<String>> liveEwonNames = fetchRealtimeTags();
      updateRealtimeTags(liveEwonNames);
   }

   /**
    * Compile a hash map containing realtime tags
    * @return realtime tag hash map
    */
   private HashMap<String, ArrayList<String>> fetchRealtimeTags() {
      // Create hashmap to store Ewons and their realtime tags
      HashMap<String, ArrayList<String>> liveEwonNames = new HashMap<String, ArrayList<String>>();

      // Create list for storing registered tags
      ArrayList<TagPath> tagList = new ArrayList<TagPath>();

      // Populate list of registered tags
      for (String tagPath : registeredTags) {
         List<String> pathParts = Arrays.asList(tagPath.split("/", 0));
         BasicTagPath propPath = new BasicTagPath(provider.getName(), pathParts);
         tagList.add(propPath);
      }

      // For each tag in list of registered tags
      for (TagPath tagPath: tagList) {
         // Indexes for Ewon name and tag name in a tag path
         final int EWON_NAME_INDEX = 0;
         final int TAG_NAME_INDEX = 1;

         // Get Ewon name and tag name
         String ewonName = tagPath.getPathComponent(EWON_NAME_INDEX);
         String tagName = tagPath.getPathComponent(TAG_NAME_INDEX);

         // Read tag value and mark as realtime if applicable
         boolean isRealtime = false;
         if (readAllTagsRealtime || realtimeEwons.contains(ewonName)) {
            isRealtime = true;
         }

         // If marked as realtime tag, add to hashmap of Ewons and their realtime tags
         if (isRealtime) {
            // If Ewon already has realtime tags in hashmap, add to list, otherwise create list and add
            if (liveEwonNames.containsKey(ewonName)) {
               liveEwonNames.get(ewonName).add(tagName);
            } else {
               ArrayList<String> tags = new ArrayList<String>();
               tags.add(tagName);
               liveEwonNames.put(ewonName, tags);
            }
         }
      }
      return liveEwonNames;
   }

   /**
    * Perform an update of realtime tags using Talk2M
    * @param liveEwonNames hash map containing realtime tags
    */
   private void updateRealtimeTags(HashMap<String, ArrayList<String>> liveEwonNames) {
      // For each Ewon with realtime tags, make Talk2M calls and update realtime tags
      for (String eWonName : liveEwonNames.keySet()) {
         try {
            // Store tag data for current Ewon
            TMResult tagData = new TMResult(comm.getLiveData(eWonName));

            // For each realtime tag on current Ewon, update value
            for (String tag : liveEwonNames.get(eWonName)) {
               Object value;
               String valueString = tagData.getTagValue(unSanitizeName(tag));

               // Identify tag type and store properly
               try {
                  // Value is an empty string
                  if (valueString.length() == 0) {
                     value = "";
                  }
                  // Value is a string
                  else if (valueString.charAt(0) == '\"') {
                     // Strip leading and trailing double quote chars
                     value = valueString.substring(1, valueString.length() - 1);
                  }
                  // Value is a number
                  else {
                     value = Double.parseDouble(valueString);
                  }
               } catch (NullPointerException e) {
                  logger.error("Tag: " + tag + " does not exist on Ewon: " + eWonName, e);
                  value = "";
               }

               // Update tag value with provider
               provider.updateValue((eWonName + "/" + tag), value, DataQuality.GOOD_DATA, new Date());
            }
         } catch (IOException e) {
            logger.error(
                    "Error connecting to eWON for live data, " + eWonName + " may be offline",
                    e);
         } catch (Exception e) {
            logger.error("Error while parsing live data", e);
         }
      }
   }

   /**
    * Execute synchronization for tags and data
    */
   protected void executeSync() {
      long start = System.currentTimeMillis();
      try {
         // If history is enabled, we use syncData. Otherwise, we use a lighter weight method to
         // just get the current
         // values from each device.
         if (isHistoryEnabled()) {
            syncHistoricalData();
         }

         // Even though history *might* include the realtime values, it doesn't if the current
         // transaction is the latest.
         // Therefore, we still need to check the realtime values, I think.
         syncLatestValues();

         // Update last sync time and duration tags
         provider.updateValue(buildTagPath(STATUS_LASTSYNCTIME), new Date(start),
               DataQuality.GOOD_DATA);
         provider.updateValue(buildTagPath(STATUS_LASTSYNCDURATION),
               System.currentTimeMillis() - start, DataQuality.GOOD_DATA);
         successCount++;

      } catch (Exception e) {
         logger.error("Error synchronizing Ewon data.", e);
         failureCount++;
      }

      // Update module status tags with latest information/statistics.
      updateStatusTags();
   }

   /**
    * Update module status tags with latest information/statistics
    */
   protected void updateStatusTags() {
      provider.updateValue(buildTagPath(STATUS_SUCCESSCOUNT), successCount, DataQuality.GOOD_DATA);
      provider.updateValue(buildTagPath(STATUS_FAILURECOUNT), failureCount, DataQuality.GOOD_DATA);
      provider.updateValue(buildTagPath(STATUS_HIST_POINT_CNT), histPoints, DataQuality.GOOD_DATA);
   }

   /**
    * Perform a synchronization of latest values
    * @throws Exception if data synchronization fails
    */
   protected void syncLatestValues() throws Exception {
      // Store data for Ewons
      EwonsData data = comm.queryEwonDevices();

      // Create list for storing Ewons to synchronize
      List<EwonData> toSync = new ArrayList<>();

      // Verify integrity of Ewons data
      if (data != null && data.getEwons() != null) {
         // For each Ewon in the data, add to toSync list if synchronization needed.
         for (EwonData ewon : data.getEwons()) {
            int id = ewon.getId();

            // Store and check last synchronization timestamp
            Date lastSync = lastSyncCache.get(id);
            if (lastSync == null || lastSync.before(ewon.getLastSync_Date())) {
               // Add Ewon to synchronization list if synchronization needed
               toSync.add(ewon);
               logger.debug("Will mark '{}' for update, device sync time={} vs local sync time={}",
                     ewon.getName(), ewon.getLastSync_Date(), lastSync);
            }
         }
      }

      // For each Ewon marked for synchronization, perform synchronization
      for (EwonData ewon : toSync) {
         try {
            // Store start timestamp
            long devstart = System.currentTimeMillis();

            // Perform tag value update
            updateTagValues(comm.queryEwon(ewon.getId()));
            logger.debug("Sync of Ewon device '{}' finished in {}", ewon.getName(),
                  FormatUtil.formatDurationSince(devstart));

            // Update last sync cache with latest sync time
            lastSyncCache.put(ewon.getId(), ewon.getLastSync_Date());
         } catch (Exception e) {
            logger.error("Error syncing Ewon device '{}/{}'", ewon.getName(), ewon.getId(), e);
         }
      }
   }

   /**
    * Perform a synchronization of historical data
    * @throws Exception if data synchronization fails
    */
   protected void syncHistoricalData() throws Exception {
      Long lastTX;
      boolean hasMore = false;
      // Loop until there is no more data to process
      do {
         // Store start timestamp and previous transaction ID
         long start = System.currentTimeMillis();
         lastTX = syncData.getTransactionId();
         logger.debug("Starting syncData for txid {}", lastTX);

         // Get and store latest data for Ewons
         EwonsData data = comm.syncData(lastTX);

         // Verify integrity of Ewons data
         if (data != null) {
            // Store new transaction ID and check for more data
            Long newTXID = data.getTransactionId();
            hasMore = data.isMoreDataAvailable();

            // Update tag values with latest data
            updateTagValues(data);
            logger.debug("Data retrieved and processed in {}. New txid: {}, hasMore: {}",
                  FormatUtil.formatDurationSince(start), newTXID, hasMore);

            // Update the internal database for next time, in order to not lose our spot after a
            // reboot.
            // However, if the txid hasn't changed, don't update- each time we update, we trigger
            // the internal db to autobackup.
            if (lastTX != newTXID) {
               syncData.setTransactionId(newTXID);
               syncData.setLastLocalSync(new Date(start));
               saveSyncData();
            }
         } else {
            hasMore = false;
         }
      } while (hasMore);
   }

   /**
    * Save synchronization information to gateway and provider
    */
   protected void saveSyncData() {
      try {
         gatewayContext.getPersistenceInterface().save(syncData);
         provider.updateValue(buildTagPath(STATUS_LAST_H_SYNCTIME), syncData.getLastLocalSync(),
               DataQuality.GOOD_DATA);
         provider.updateValue(buildTagPath(STATUS_LASTSYNCID), syncData.getTransactionId(),
               DataQuality.GOOD_DATA);
      } catch (Exception e) {
         logger.error("Error saving and updating sync information.", e);
      }
   }

   /**
    * Update tag values with given multiple Ewons data
    * @param data data for multiple Ewons
    */
   protected void updateTagValues(EwonsData data) {
      // Verify integrity of Ewons data
      if (data == null || data.getEwons() == null) {
         return;
      }

      // Call updateTagValues on each Ewon's data
      for (EwonData ewon : data.getEwons()) {
         updateTagValues(ewon);
      }
   }

   /**
    * Create a TagValue object with given tag information and value
    * @param v tag value
    * @param quality tag data quality
    * @param ts timestamp
    * @param dtype tag data type
    * @return created TagValue object
    */
   protected TagValue buildTagValue(Object v, DataQuality quality, Date ts, DataType dtype) {
      // Store given tag value
      Object vToUse = v;

      // Verify integrity of given tag value
      if (v != null) {
         // ConvertNumber objects (i.e. BigDecimal) to standard values
         if (v instanceof Number) {
            Number bd = (Number) v;
            switch (dtype.getTypeClass()) {
               case String:
                  vToUse = bd.toString();
                  break;
               case Float:
                  vToUse = bd.doubleValue();
                  break;
               default:
                  vToUse = bd.longValue();
                  break;
            }
         }
      }

      // Create and return TagValue with given and processed information and value
      return new BasicTagValue(TypeUtilities.coerceNullSafe(vToUse, dtype.getJavaType()), quality,
            ts);
   }

   /**
    * Create a HistoricalTagValue object with given tag information and value
    * @param path tag path
    * @param dtc tag data type class
    * @param v tag value
    * @return created HistoricalTagValue object
    */
   protected HistoricalTagValue buildHTV(TagPath path, DataTypeClass dtc, TagValue v) {
      return new PackedHistoricalTagValue(path, dtc,
            dtc == DataTypeClass.Float ? InterpolationMode.Analog_Compressed
                  : InterpolationMode.Discrete,
            TimestampSource.Value, v);
   }

   /**
    * Update tag values with given Ewon data
    * @param data Ewon data
    */
   protected void updateTagValues(EwonData data) {
      // Verify integrity of given Ewon data
      if (data == null || data.getTags() == null) {
         return;
      }

      // Store device information locally
      String device = data.getName();
      Date deviceDate = data.getLastSync_Date();
      Date latestValueTS = null;

      // Verify integrity of given Ewon data getTags, duplicate check
      if (data.getTags() != null) {
         // Create basic tag history set
         BasicScanclassHistorySet historySet =
               new BasicScanclassHistorySet(provider.getName(), "_exempt_", -1);

         // Add AllRealtime tag under Ewon _config tag folder
         if (!registeredEwons.contains(device))
         {
            // Create path to Ewon's all realtime tag
            TagPath ewonRealtimePath = buildTagPath(device + "/_config/AllRealtime");

            // Configure Ewon's all realtime tag
            provider.configureTag(ewonRealtimePath, DataType.Boolean, TagType.Custom);
            provider.updateValue(ewonRealtimePath.toStringPartial(), false, DataQuality.GOOD_DATA);

            // Add Ewon to registered Ewon list
            registeredEwons.add(device);

            // Register all realtime tag write handler with provider
            provider.registerWriteHandler(ewonRealtimePath.toStringPartial(), new WriteHandler() {
               public Quality write(TagPath tagPath, Object o) {
                  try {
                     boolean realtimeEnabledBool = (boolean) o;
                     if (realtimeEnabledBool) {
                        realtimeEwons.add(device);
                     }
                     else {
                        realtimeEwons.remove(device);
                     }
                     provider.updateValue(ewonRealtimePath.toStringPartial(), o, DataQuality.GOOD_DATA);
                  } catch (ClassCastException e) {
                      logger.error(
                              "Writing Ewon AllRealtime tag has failed. Incorrect datatype.",
                              e);
                      provider.configureTag(ewonRealtimePath, DataType.Boolean, TagType.Custom);
                      provider.updateValue(ewonRealtimePath, Boolean.FALSE, DataQuality.GOOD_DATA);
                  } catch (Exception e) {
                      logger.error("Writing Ewon AllRealtime tag has failed.", e);
                  }
                  return DataQuality.GOOD_DATA;
               }
            });
         }


         // Loop for each tag in given Ewon data
         for (Tag t : data.getTags()) {
            // Try to create tag dataset
            try {
               // Get tag path and data type information
               TagPath p = buildTagPath(device, t.getName());
               DataType dType = EwonUtil.toDataType(t.getDataType());
               DataTypeClass dtc = dType.getTypeClass();

               // Check tag name format and display warning if necessary
               if (!replaceUnderscore && !t.getName().matches(EwonConsts.ALLOWED_TAGNAME_REGEX)) {
                  logger.error(String.format("The tag %s has an unsupported name. " +
                          "Supported tag names must begin with an alphanumeric or underscore, " +
                          "followed by any number of alphanumerics, underscores, spaces, " +
                          "or the following: \' - : ( )", p));
               }
               else if (replaceUnderscore && !t.getName().matches(EwonConsts.ALLOWED_TAGNAME_REGEX_NO_UNDERSCORE)) {
                  logger.error(String.format("The tag %s has an unsupported name. " +
                          "Supported tag names must begin with an alphanumeric or period, " +
                          "followed by any number of alphanumerics, spaces, " +
                          "or the following: \' . - : ( ) To enable support for tag " +
                          "names containing underscores, disable the \"Tag Names Contain Periods?\" " +
                          "option.", p));
               }
               else {
                  // Verify tag history information integrity, then
                  // add historical tag values to tag history set
                  // Note: No history enabled check here, because it can be manually triggered.
                  if (!StringUtils.isBlank(tagHistoryStore) && t.getHistory() != null) {
                     for (DataPoint d : t.getHistory()) {
                        HistoricalTagValue htv = buildHTV(p, dtc,
                                buildTagValue(d.getValue(), EwonUtil.toQuality(d.getQuality()),
                                        EwonUtil.toDate(d.getDate()), dType));
                        historySet.add(htv);
                        histPoints++;
                     }
                  }

                  // If tag write callback not registered, register tag write callback
                  if (!registeredTags.contains(p.toStringPartial())) {
                     // Register tag
                     registeredTags.add(p.toStringPartial());

                     // Register tag write handler with provider
                     provider.registerWriteHandler(p.toStringPartial(), new WriteHandler() {
                        public Quality write(TagPath tagPath, Object o) {
                           try {
                              // Unsanitize tag name if necessary
                              String tagName =
                                      replaceUnderscore ? unSanitizeName(tagPath.getItemName())
                                              : tagPath.getItemName();

                              // Store write value from object
                              String writeValue = o.toString();

                              // Handle boolean values
                              if (o instanceof Boolean) {
                                 writeValue = (Boolean) o ? "1" : "0";
                              }

                              // Perform tag write and update value with provider
                              comm.writeTag(tagPath.getParentPath().getItemName(), tagName,
                                      writeValue);
                              provider.updateValue(p.toStringPartial(), o, TagQuality.GOOD);
                           } catch (Exception e) {
                              logger.error("Writing tag to Ewon via Talk2M API Failed");
                           }
                           return TagQuality.GOOD;
                        }
                     });
                  }

                  // Build tag value for realtime update
                  TagValue v = buildTagValue(t.getValue(), EwonUtil.toQuality(t.getQuality()),
                          deviceDate, dType);

                  // Configure tag data type
                  provider.configureTag(p, dType, TagType.Custom);

                  // Update tag value if read all tags in realtime is enabled
                  if (!readAllTagsRealtime) {
                     provider.updateValue(p.toStringPartial(), v.getValue(), v.getQuality(), v.getTimestamp());
                  }

                  // Output success
                  logger.trace("Updated realtime value for '{}' [id={}] to {}", p, t.getId(), v);
               }
            } catch (Exception e) {
               logger.error("Unable to create dataset for tag '{}/{}'", device, t.getName(), e);
            }
         }

         // If there is tag history data, sort if ascending by timestamp, and store it
         if (historySet.size() > 0) {
            try {
               historySet.sort((a, b) -> a.getTimestamp().compareTo(b.getTimestamp()));
               latestValueTS = historySet.get(historySet.size() - 1).getTimestamp();
               gatewayContext.getHistoryManager().storeHistory(tagHistoryStore, historySet);
            } catch (Exception e) {
               logger.error("Error storing history for device '{}'", device, e);
            }
         }
      }

      // Verify integrity of sync data and update timestamps
      if (syncData != null) {
         syncData.setLastRemoteSync(deviceDate);
         if (latestValueTS != null) {
            syncData.setLastHistoryTimestamp(latestValueTS);
         }
      }
   }

   /**
    * Convert an unsanitized name to sanitized name
    * @param name unsanitized name
    * @return sanitized name
    */
   protected String sanitizeName(String name) {
      return name.replaceAll("[\\.]", "_");
   }

   /**
    * Convert a sanitized name to unsanitized name
    * @param name sanitized name
    * @return unsanitized name
    */
   protected String unSanitizeName(String name) {
      return name.replaceAll("[\\_]", ".");
   }

   /**
    * Build a tag path string using given tag name.
    * @param tagName tag name
    * @return tag path string
    */
   protected TagPath buildTagPath(String tagName) {
      return TagPathParser.parseSafe(provider.getName(), sanitizeName(tagName));
   }

   /**
    * Build a tag path string using given Ewon name and tag name. Format: [Ewon Name]/[tag name]
    * @param ewonName name of Ewon
    * @param tagName name of tag
    * @return tag path string
    * @throws IOException if unable to parse tag path
    */
   protected TagPath buildTagPath(String ewonName, String tagName) throws IOException {
      return TagPathParser.parse(provider.getName(), sanitizeName(ewonName + "/" + tagName));
   }
}