package net.i2p;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.Properties;
import java.util.Random;
import java.util.Set;

import net.i2p.client.naming.NamingService;
import net.i2p.crypto.AESEngine;
import net.i2p.crypto.CryptixAESEngine;
import net.i2p.crypto.DSAEngine;
import net.i2p.crypto.DummyDSAEngine;
import net.i2p.crypto.DummyElGamalEngine;
//import net.i2p.crypto.DummyPooledRandomSource;
import net.i2p.crypto.ElGamalAESEngine;
import net.i2p.crypto.ElGamalEngine;
import net.i2p.crypto.HMAC256Generator;
import net.i2p.crypto.HMACGenerator;
import net.i2p.crypto.KeyGenerator;
import net.i2p.crypto.SHA256Generator;
import net.i2p.crypto.SessionKeyManager;
import net.i2p.crypto.TransientSessionKeyManager;
import net.i2p.data.Base64;
import net.i2p.data.RoutingKeyGenerator;
import net.i2p.internal.InternalClientManager;
import net.i2p.stat.StatManager;
import net.i2p.update.UpdateManager;
import net.i2p.util.Clock;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.FileUtil;
import net.i2p.util.FortunaRandomSource;
import net.i2p.util.I2PProperties;
import net.i2p.util.KeyRing;
import net.i2p.util.LogManager;
//import net.i2p.util.PooledRandomSource;
import net.i2p.util.PortMapper;
import net.i2p.util.RandomSource;
import net.i2p.util.SecureDirectory;
import net.i2p.util.SimpleScheduler;
import net.i2p.util.SimpleTimer;
import net.i2p.util.SimpleTimer2;
import net.i2p.util.I2PProperties.I2PPropertyCallback;

/**
 * <p>Provide a base scope for accessing singletons that I2P exposes.  Rather than
 * using the traditional singleton, where any component can access the component
 * in question directly, all of those I2P related singletons are exposed through
 * a particular I2PAppContext.  This helps not only with understanding their use
 * and the components I2P exposes, but it also allows multiple isolated 
 * environments to operate concurrently within the same JVM - particularly useful
 * for stubbing out implementations of the rooted components and simulating the
 * software's interaction between multiple instances.</p>
 *
 * As a simplification, there is also a global context - if some component needs
 * access to one of the singletons but doesn't have its own context from which
 * to root itself, it binds to the I2PAppContext's globalAppContext(), which is
 * the first context that was created within the JVM, or a new one if no context
 * existed already.  This functionality is often used within the I2P core for 
 * logging - e.g. <pre>
 *     private static final Log _log = new Log(someClass.class);
 * </pre>
 * It is for this reason that applications that care about working with multiple
 * contexts should build their own context as soon as possible (within the main(..))
 * so that any referenced components will latch on to that context instead of 
 * instantiating a new one.  However, there are situations in which both can be
 * relevant.
 *
 */
public class I2PAppContext {
    /** the context that components without explicit root are bound */
    protected static volatile I2PAppContext _globalAppContext;
    
    protected final I2PProperties _overrideProps;
    
    private StatManager _statManager;
    private SessionKeyManager _sessionKeyManager;
    private NamingService _namingService;
    private ElGamalEngine _elGamalEngine;
    private ElGamalAESEngine _elGamalAESEngine;
    private AESEngine _AESEngine;
    private LogManager _logManager;
    private HMACGenerator _hmac;
    private HMAC256Generator _hmac256;
    private SHA256Generator _sha;
    protected Clock _clock; // overridden in RouterContext
    private DSAEngine _dsa;
    private RoutingKeyGenerator _routingKeyGenerator;
    private RandomSource _random;
    private KeyGenerator _keyGenerator;
    protected KeyRing _keyRing; // overridden in RouterContext
    private SimpleScheduler _simpleScheduler;
    private SimpleTimer _simpleTimer;
    private SimpleTimer2 _simpleTimer2;
    private final PortMapper _portMapper;
    private volatile boolean _statManagerInitialized;
    private volatile boolean _sessionKeyManagerInitialized;
    private volatile boolean _namingServiceInitialized;
    private volatile boolean _elGamalEngineInitialized;
    private volatile boolean _elGamalAESEngineInitialized;
    private volatile boolean _AESEngineInitialized;
    private volatile boolean _logManagerInitialized;
    private volatile boolean _hmacInitialized;
    private volatile boolean _hmac256Initialized;
    private volatile boolean _shaInitialized;
    protected volatile boolean _clockInitialized; // used in RouterContext
    private volatile boolean _dsaInitialized;
    private volatile boolean _routingKeyGeneratorInitialized;
    private volatile boolean _randomInitialized;
    private volatile boolean _keyGeneratorInitialized;
    protected volatile boolean _keyRingInitialized; // used in RouterContext
    private volatile boolean _simpleSchedulerInitialized;
    private volatile boolean _simpleTimerInitialized;
    private volatile boolean _simpleTimer2Initialized;
    protected final Set<Runnable> _shutdownTasks;
    private final File _baseDir;
    private final File _configDir;
    private final File _routerDir;
    private final File _pidDir;
    private final File _logDir;
    private final File _appDir;
    private volatile File _tmpDir;
    // split up big lock on this to avoid deadlocks
    private final Object _lock1 = new Object(), _lock2 = new Object(), _lock3 = new Object(), _lock4 = new Object(),
                         _lock5 = new Object(), _lock6 = new Object(), _lock7 = new Object(), _lock8 = new Object(),
                         _lock9 = new Object(), _lock10 = new Object(), _lock11 = new Object(), _lock12 = new Object(),
                         _lock13 = new Object(), _lock14 = new Object(), _lock15 = new Object(), _lock16 = new Object(),
                         _lock17 = new Object(), _lock18 = new Object(), _lock19 = new Object(), _lock20 = new Object();

    /**
     * Pull the default context, creating a new one if necessary, else using 
     * the first one created.
     *
     * Warning - do not save the returned value, or the value of any methods below,
     * in a static field, or you will get the old context if a new router is
     * started in the same JVM after the first is shut down,
     * e.g. on Android.
     */
    public static I2PAppContext getGlobalContext() { 
        // skip the global lock - _gAC must be volatile
        // http://www.cs.umd.edu/~pugh/java/memoryModel/DoubleCheckedLocking.html
        I2PAppContext rv = _globalAppContext;
        if (rv != null)
            return rv;

        synchronized (I2PAppContext.class) {
            if (_globalAppContext == null) {
                _globalAppContext = new I2PAppContext(false, null);
            }
        }
        return _globalAppContext; 
    }
    
    /**
     * Pull the default context, WITHOUT creating a new one.
     * Use this in static methods used early in router initialization,
     * where creating a context messes things up.
     *
     * @return context or null
     * @since 0.8.2
     */
    public static I2PAppContext getCurrentContext() { 
        return _globalAppContext; 
    }
    
    /**
     * Create a brand new context.
     * WARNING: In almost all cases, you should use getGlobalContext() instead,
     * to avoid creating additional contexts, which may spawn numerous
     * additional resources and threads, and may be the cause of logging
     * problems or hard-to-isolate bugs.
     */
    public I2PAppContext() {
        this(true, null);
    }
    
    /**
     * Create a brand new context.
     * WARNING: In almost all cases, you should use getGlobalContext() instead,
     * to avoid creating additional contexts, which may spawn numerous
     * additional resources and threads, and may be the cause of logging
     * problems or hard-to-isolate bugs.
     */
    public I2PAppContext(Properties envProps) {
        this(true, envProps);
    }
    
    /**
     * Create a brand new context.
     * WARNING: In almost all cases, you should use getGlobalContext() instead,
     * to avoid creating additional contexts, which may spawn numerous
     * additional resources and threads, and may be the cause of logging
     * problems or hard-to-isolate bugs.
     *
     * @param doInit should this context be used as the global one (if necessary)?
     *               Will only apply if there is no global context now.
     */
    private I2PAppContext(boolean doInit, Properties envProps) {
      synchronized (I2PAppContext.class) { 
        _overrideProps = new I2PProperties();
        if (envProps != null)
            _overrideProps.putAll(envProps);
        _shutdownTasks = new ConcurrentHashSet(32);
        _portMapper = new PortMapper(this);
    
   /*
    *  Directories. These are all set at instantiation and will not be changed by
    *  subsequent property changes.
    *  All properties, if set, should be absolute paths.
    *
    *  Name	Property 	Method		Files
    *  -----	-------- 	-----		-----
    *  Base	i2p.dir.base	getBaseDir()	lib/, webapps/, docs/, geoip/, licenses/, ...
    *  Temp	i2p.dir.temp	getTempDir()	Temporary files
    *  Config	i2p.dir.config	getConfigDir()	*.config, hosts.txt, addressbook/, ...
    *
    *  (the following all default to the same as Config)
    *
    *  PID	i2p.dir.pid	getPIDDir()	router.ping
    *  Router	i2p.dir.router	getRouterDir()	netDb/, peerProfiles/, router.*, keyBackup/, ...
    *  Log	i2p.dir.log	getLogDir()	logs/
    *  App	i2p.dir.app	getAppDir()	eepsite/, ...
    *
    *  Note that we can't control where the wrapper puts its files.
    *
    *  The app dir is where all data files should be. Apps should always read and write files here,
    *  using a constructor such as:
    *
    *       String path = mypath;
    *       File f = new File(path);
    *       if (!f.isAbsolute())
    *           f = new File(_context.geAppDir(), path);
    *
    *  and never attempt to access files in the CWD using
    *
    *       File f = new File("foo");
    *
    *  An app should assume the CWD is not writable.
    *
    *  Here in I2PAppContext, all the dirs default to CWD.
    *  However these will be different in RouterContext, as Router.java will set
    *  the properties in the RouterContext constructor.
    *
    *  Apps should never need to access the base dir, which is the location of the base I2P install.
    *  However this is provided for the router's use, and for backward compatibility should an app
    *  need to look there as well.
    *
    *  All dirs except the base are created if they don't exist, but the creation will fail silently.
    *  @since 0.7.6
    */

        String s = getProperty("i2p.dir.base", System.getProperty("user.dir"));
        _baseDir = new File(s);

        // config defaults to base
        s = getProperty("i2p.dir.config");
        if (s != null) {
            _configDir = new SecureDirectory(s);
            if (!_configDir.exists())
                _configDir.mkdir();
        } else {
            _configDir = _baseDir;
        }

        // router defaults to config
        s = getProperty("i2p.dir.router");
        if (s != null) {
            _routerDir = new SecureDirectory(s);
            if (!_routerDir.exists())
                _routerDir.mkdir();
        } else {
            _routerDir = _configDir;
        }

        // pid defaults to router directory (as of 0.8.12, was system temp dir previously)
        s = getProperty("i2p.dir.pid");
        if (s != null) {
            _pidDir = new SecureDirectory(s);
            if (!_pidDir.exists())
                _pidDir.mkdir();
        } else {
            _pidDir = _routerDir;
        }

        // these all default to router
        s = getProperty("i2p.dir.log");
        if (s != null) {
            _logDir = new SecureDirectory(s);
            if (!_logDir.exists())
                _logDir.mkdir();
        } else {
            _logDir = _routerDir;
        }

        s = getProperty("i2p.dir.app");
        if (s != null) {
            _appDir = new SecureDirectory(s);
            if (!_appDir.exists())
                _appDir.mkdir();
        } else {
            _appDir = _routerDir;
        }
        /******
        (new Exception("Initialized by")).printStackTrace();
        System.err.println("Base directory:   " + _baseDir.getAbsolutePath());
        System.err.println("Config directory: " + _configDir.getAbsolutePath());
        System.err.println("Router directory: " + _routerDir.getAbsolutePath());
        System.err.println("App directory:    " + _appDir.getAbsolutePath());
        System.err.println("Log directory:    " + _logDir.getAbsolutePath());
        System.err.println("PID directory:    " + _pidDir.getAbsolutePath());
        System.err.println("Temp directory:   " + getTempDir().getAbsolutePath());
        ******/

        if (doInit) {
            if (_globalAppContext == null) {
                _globalAppContext = this;
            } else {
                System.out.println("Warning - New context not replacing old one, you now have a second one");
                (new Exception("I did it")).printStackTrace();
            }
        }
      } // synch
    }

    /**
     *  This is the installation dir, often referred to as $I2P.
     *  Applilcations should consider this directory read-only and never
     *  attempt to write to it.
     *  It may actually be read-only on a multi-user installation.
     *  The config files in this directory are templates for user
     *  installations and should not be accessed by applications.
     *  The only thing that may be useful in here is the lib/ dir
     *  containing the .jars.
     *  @since 0.7.6
     *  @return dir constant for the life of the context
     */
    public File getBaseDir() { return _baseDir; }

    /**
     *  The base dir for config files.
     *  Applications may use this to access router configuration files if necessary.
     *  Usually ~/.i2p on Linux and %APPDIR%\I2P on Windows.
     *  In installations originally installed with 0.7.5 or earlier, and in
     *  "portable" installations, this will be the same as the base dir.
     *  @since 0.7.6
     *  @return dir constant for the life of the context
     */
    public File getConfigDir() { return _configDir; }

    /**
     *  Where the router keeps its files.
     *  Applications should not use this.
     *  The same as the config dir for now.
     *  @since 0.7.6
     *  @return dir constant for the life of the context
     */
    public File getRouterDir() { return _routerDir; }

    /**
     *  Where router.ping goes.
     *  Applications should not use this.
     *  The same as the router dir by default as of 0.8.12
     *  Was the same as the system temp dir prior to that.
     *  Which was a problem for multi-user installations.
     *  @since 0.7.6
     *  @return dir constant for the life of the context
     */
    public File getPIDDir() { return _pidDir; }

    /**
     *  Where the router keeps its log directory.
     *  Applications should not use this.
     *  The same as the config dir for now.
     *  (i.e. ~/.i2p, NOT ~/.i2p/logs)
     *  @since 0.7.6
     *  @return dir constant for the life of the context
     */
    public File getLogDir() { return _logDir; }

    /**
     *  Where applications may store data.
     *  The same as the config dir for now, but may change in the future.
     *  Apps should be careful not to overwrite router files.
     *  @since 0.7.6
     *  @return dir constant for the life of the context
     */
    public File getAppDir() { return _appDir; }

    /**
     *  Where anybody may store temporary data.
     *  This is a directory created in the system temp dir on the
     *  first call in this context, and is deleted on JVM exit.
     *  Applications should create their own directory inside this directory
     *  to avoid collisions with other apps.
     *  @since 0.7.6
     *  @return dir constant for the life of the context
     */
    public File getTempDir() {
        // fixme don't synchronize every time
        synchronized (_lock1) {
            if (_tmpDir == null) {
                String d = getProperty("i2p.dir.temp", System.getProperty("java.io.tmpdir"));
                // our random() probably isn't warmed up yet
                byte[] rand = new byte[6];
                (new Random()).nextBytes(rand);
                String f = "i2p-" + Base64.encode(rand) + ".tmp";
                _tmpDir = new SecureDirectory(d, f);
                if (_tmpDir.exists()) {
                    // good or bad ? loop and try again?
                } else if (_tmpDir.mkdir()) {
                    _tmpDir.deleteOnExit();
                } else {
                    System.err.println("Could not create temp dir " + _tmpDir.getAbsolutePath());
                    _tmpDir = new SecureDirectory(_routerDir, "tmp");
                    _tmpDir.mkdir();
                }
            }
        }
        return _tmpDir;
    }

    /** don't rely on deleteOnExit() */
    public void deleteTempDir() {
        synchronized (_lock1) {
            if (_tmpDir != null) {
                FileUtil.rmdir(_tmpDir, false);
                _tmpDir = null;
            }
        }
    }

    /**
     * Access the configuration attributes of this context, using properties 
     * provided during the context construction, or falling back on 
     * System.getProperty if no properties were provided during construction
     * (or the specified prop wasn't included).
     *
     */
    public String getProperty(String propName) {
        if (_overrideProps != null) {
            if (_overrideProps.containsKey(propName))
                return _overrideProps.getProperty(propName);
        }
        return System.getProperty(propName);
    }

    /**
     * Access the configuration attributes of this context, using properties 
     * provided during the context construction, or falling back on 
     * System.getProperty if no properties were provided during construction
     * (or the specified prop wasn't included).
     *
     */
    public String getProperty(String propName, String defaultValue) {
        if (_overrideProps != null) {
            if (_overrideProps.containsKey(propName))
                return _overrideProps.getProperty(propName, defaultValue);
        }
        return System.getProperty(propName, defaultValue);
    }

    /**
     * Return an int with an int default
     */
    public int getProperty(String propName, int defaultVal) {
        String val = null;
        if (_overrideProps != null) {
            val = _overrideProps.getProperty(propName);
            if (val == null)
                val = System.getProperty(propName);
        }
        int ival = defaultVal;
        if (val != null) {
            try {
                ival = Integer.parseInt(val);
            } catch (NumberFormatException nfe) {}
        }
        return ival;
    }

    /**
     * Return a long with a long default
     * @since 0.9.4
     */
    public long getProperty(String propName, long defaultVal) {
        String val = null;
        if (_overrideProps != null) {
            val = _overrideProps.getProperty(propName);
            if (val == null)
                val = System.getProperty(propName);
        }
        long rv = defaultVal;
        if (val != null) {
            try {
                rv = Long.parseLong(val);
            } catch (NumberFormatException nfe) {}
        }
        return rv;
    }

    /**
     * Return a boolean with a boolean default
     * @since 0.7.12
     */
    public boolean getProperty(String propName, boolean defaultVal) {
        String val = getProperty(propName);
        if (val == null)
            return defaultVal;
        return Boolean.parseBoolean(val);
    }

    /**
     * Default false
     * @since 0.7.12
     */
    public boolean getBooleanProperty(String propName) {
        return Boolean.parseBoolean(getProperty(propName));
    }

    /**
     * @since 0.7.12
     */
    public boolean getBooleanPropertyDefaultTrue(String propName) {
        return getProperty(propName, true);
    }

    /**
     * Access the configuration attributes of this context, listing the properties 
     * provided during the context construction, as well as the ones included in
     * System.getProperties.
     *
     * WARNING - not overridden in RouterContext, doesn't contain router config settings,
     * use getProperties() instead.
     *
     * @return set of Strings containing the names of defined system properties
     */
    public Set getPropertyNames() { 
        // clone to avoid ConcurrentModificationException
        Set names = new HashSet(((Properties) System.getProperties().clone()).keySet());
        if (_overrideProps != null)
            names.addAll(_overrideProps.keySet());
        return names;
    }
    
    /**
     * Access the configuration attributes of this context, listing the properties 
     * provided during the context construction, as well as the ones included in
     * System.getProperties.
     *
     * @return new Properties with system and context properties
     * @since 0.8.4
     */
    public Properties getProperties() { 
        // clone to avoid ConcurrentModificationException
        Properties rv = (Properties) System.getProperties().clone();
        rv.putAll(_overrideProps);
        return rv;
    }
    
    /**
     * Add a callback, which will fire upon changes in the property
     * given in the specific callback.
     * Unimplemented in I2PAppContext: this only makes sense in a router context.
     * @param callback The implementation of the callback.
     */
    public void addPropertyCallback(I2PPropertyCallback callback) {}
    
    /**
     * The statistics component with which we can track various events
     * over time.
     */
    public StatManager statManager() { 
        if (!_statManagerInitialized)
            initializeStatManager();
        return _statManager;
    }

    private void initializeStatManager() {
        synchronized (_lock2) {
            if (_statManager == null)
                _statManager = new StatManager(this);
            _statManagerInitialized = true;
        }
    }
    
    /**
     * The session key manager which coordinates the sessionKey / sessionTag
     * data.  This component allows transparent operation of the 
     * ElGamal/AES+SessionTag algorithm, and contains all of the session tags
     * for one particular application.
     *
     * This is deprecated for client use, it should be used only by the router
     * as its own key manager. Not that clients are doing end-to-end crypto anyway.
     *
     * For client crypto within the router,
     * use RouterContext.clientManager.getClientSessionKeyManager(dest)
     *
     */
    public SessionKeyManager sessionKeyManager() { 
        if (!_sessionKeyManagerInitialized)
            initializeSessionKeyManager();
        return _sessionKeyManager;
    }

    private void initializeSessionKeyManager() {
        synchronized (_lock3) {
            if (_sessionKeyManager == null) 
                //_sessionKeyManager = new PersistentSessionKeyManager(this);
                _sessionKeyManager = new TransientSessionKeyManager(this);
            _sessionKeyManagerInitialized = true;
        }
    }
    
    /**
     * Pull up the naming service used in this context.  The naming service itself
     * works by querying the context's properties, so those props should be 
     * specified to customize the naming service exposed.
     */
    public NamingService namingService() { 
        if (!_namingServiceInitialized)
            initializeNamingService();
        return _namingService;
    }

    private void initializeNamingService() {
        synchronized (_lock4) {
            if (_namingService == null) {
                _namingService = NamingService.createInstance(this);
            }
            _namingServiceInitialized = true;
        }
    }
    
    /**
     * This is the ElGamal engine used within this context.  While it doesn't
     * really have anything substantial that is context specific (the algorithm
     * just does the algorithm), it does transparently use the context for logging
     * its performance and activity.  In addition, the engine can be swapped with
     * the context's properties (though only someone really crazy should mess with
     * it ;)
     */
    public ElGamalEngine elGamalEngine() {
        if (!_elGamalEngineInitialized)
            initializeElGamalEngine();
        return _elGamalEngine;
    }

    private void initializeElGamalEngine() {
        synchronized (_lock5) {
            if (_elGamalEngine == null) {
                if ("off".equals(getProperty("i2p.encryption", "on")))
                    _elGamalEngine = new DummyElGamalEngine(this);
                else
                    _elGamalEngine = new ElGamalEngine(this);
            }
            _elGamalEngineInitialized = true;
        }
    }
    
    /**
     * Access the ElGamal/AES+SessionTag engine for this context.  The algorithm
     * makes use of the context's sessionKeyManager to coordinate transparent
     * access to the sessionKeys and sessionTags, as well as the context's elGamal
     * engine (which in turn keeps stats, etc).
     *
     */
    public ElGamalAESEngine elGamalAESEngine() {
        if (!_elGamalAESEngineInitialized)
            initializeElGamalAESEngine();
        return _elGamalAESEngine;
    }

    private void initializeElGamalAESEngine() {
        synchronized (_lock6) {
            if (_elGamalAESEngine == null)
                _elGamalAESEngine = new ElGamalAESEngine(this);
            _elGamalAESEngineInitialized = true;
        }
    }
    
    /**
     * Ok, I'll admit it.  there is no good reason for having a context specific
     * AES engine.  We dont really keep stats on it, since its just too fast to
     * matter.  Though for the crazy people out there, we do expose a way to 
     * disable it.
     */
    public AESEngine aes() {
        if (!_AESEngineInitialized)
            initializeAESEngine();
        return _AESEngine;
    }

    private void initializeAESEngine() {
        synchronized (_lock7) {
            if (_AESEngine == null) {
                if ("off".equals(getProperty("i2p.encryption", "on")))
                    _AESEngine = new AESEngine(this);
                else
                    _AESEngine = new CryptixAESEngine(this);
            }
            _AESEngineInitialized = true;
        }
    }
    
    /**
     * Query the log manager for this context, which may in turn have its own
     * set of configuration settings (loaded from the context's properties).  
     * Each context's logManager keeps its own isolated set of Log instances with
     * their own log levels, output locations, and rotation configuration.
     */
    public LogManager logManager() { 
        if (!_logManagerInitialized)
            initializeLogManager();
        return _logManager;
    }

    private void initializeLogManager() {
        synchronized (_lock8) {
            if (_logManager == null)
                _logManager = new LogManager(this);
            _logManagerInitialized = true;
        }
    }

    /** 
     * There is absolutely no good reason to make this context specific, 
     * other than for consistency, and perhaps later we'll want to 
     * include some stats.
     *
     * DEPRECATED - non-standard and used only by SSU.
     * To be moved from context to SSU.
     */
    public HMACGenerator hmac() { 
        if (!_hmacInitialized)
            initializeHMAC();
        return _hmac;
    }

    private void initializeHMAC() {
        synchronized (_lock9) {
            if (_hmac == null) {
                _hmac= new HMACGenerator(this);
            }
            _hmacInitialized = true;
        }
    }

    /** @deprecated used only by syndie */
    public HMAC256Generator hmac256() {
        if (!_hmac256Initialized)
            initializeHMAC256();
        return _hmac256;
    }

    /** @deprecated used only by syndie */
    private void initializeHMAC256() {
        synchronized (_lock10) {
            if (_hmac256 == null) {
                _hmac256 = new HMAC256Generator(this);
            }
            _hmac256Initialized = true;
        }
    }
    
    /**
     * Our SHA256 instance (see the hmac discussion for why its context specific)
     *
     */
    public SHA256Generator sha() { 
        if (!_shaInitialized)
            initializeSHA();
        return _sha;
    }

    private void initializeSHA() {
        synchronized (_lock11) {
            if (_sha == null)
                _sha= new SHA256Generator(this);
            _shaInitialized = true;
        }
    }
    
    /**
     * Our DSA engine (see HMAC and SHA above)
     *
     */
    public DSAEngine dsa() { 
        if (!_dsaInitialized)
            initializeDSA();
        return _dsa;
    }

    private void initializeDSA() {
        synchronized (_lock12) {
            if (_dsa == null) {
                if ("off".equals(getProperty("i2p.encryption", "on")))
                    _dsa = new DummyDSAEngine(this);
                else
                    _dsa = new DSAEngine(this);
            }
            _dsaInitialized = true;
        }
    }
    
    /**
     * Component to generate ElGamal, DSA, and Session keys.  For why it is in
     * the appContext, see the DSA, HMAC, and SHA comments above.
     */
    public KeyGenerator keyGenerator() {
        if (!_keyGeneratorInitialized)
            initializeKeyGenerator();
        return _keyGenerator;
    }

    private void initializeKeyGenerator() {
        synchronized (_lock13) {
            if (_keyGenerator == null)
                _keyGenerator = new KeyGenerator(this);
            _keyGeneratorInitialized = true;
        }
    }
    
    /**
     * The context's synchronized clock, which is kept context specific only to
     * enable simulators to play with clock skew among different instances.
     *
     */
    public Clock clock() {
        if (!_clockInitialized)
            initializeClock();
        return _clock;
    }

    protected void initializeClock() { // overridden in RouterContext
        synchronized (_lock14) {
            if (_clock == null)
                _clock = new Clock(this);
            _clockInitialized = true;
        }
    }
    
    /**
     * Determine how much do we want to mess with the keys to turn them 
     * into something we can route.  This is context specific because we 
     * may want to test out how things react when peers don't agree on 
     * how to skew.
     *
     */
    public RoutingKeyGenerator routingKeyGenerator() {
        if (!_routingKeyGeneratorInitialized)
            initializeRoutingKeyGenerator();
        return _routingKeyGenerator;
    }

    private void initializeRoutingKeyGenerator() {
        synchronized (_lock15) {
            if (_routingKeyGenerator == null)
                _routingKeyGenerator = new RoutingKeyGenerator(this);
            _routingKeyGeneratorInitialized = true;
        }
    }
    
    /**
     * Basic hash map
     */
    public KeyRing keyRing() {
        if (!_keyRingInitialized)
            initializeKeyRing();
        return _keyRing;
    }

    protected void initializeKeyRing() {
        synchronized (_lock16) {
            if (_keyRing == null)
                _keyRing = new KeyRing();
            _keyRingInitialized = true;
        }
    }
    
    /**
     * [insert snarky comment here]
     *
     */
    public RandomSource random() {
        if (!_randomInitialized)
            initializeRandom();
        return _random;
    }

    private void initializeRandom() {
        synchronized (_lock17) {
            if (_random == null) {
                //if (true)
                    _random = new FortunaRandomSource(this);
                //else if ("true".equals(getProperty("i2p.weakPRNG", "false")))
                //    _random = new DummyPooledRandomSource(this);
                //else
                //    _random = new PooledRandomSource(this);
            }
            _randomInitialized = true;
        }
    }

    /**
     *  WARNING - Shutdown tasks are not executed in an I2PAppContext.
     *  You must be in a RouterContext for the tasks to be executed
     *  at shutdown.
     *  This method moved from Router in 0.7.1 so that clients
     *  may use it without depending on router.jar.
     *  @since 0.7.1
     */
    public void addShutdownTask(Runnable task) {
        _shutdownTasks.add(task);
    }
    
    /**
     *  @return an unmodifiable Set
     *  @since 0.7.1
     */
    public Set<Runnable> getShutdownTasks() {
        return Collections.unmodifiableSet(_shutdownTasks);
    }
    
    /**
     *  Use this instead of context instanceof RouterContext
     *  @since 0.7.9
     */
    public boolean isRouterContext() {
        return false;
    }

    /**
     *  Use this to connect to the router in the same JVM.
     *  @return always null in I2PAppContext, the client manager if in RouterContext
     *  @since 0.8.3
     */
    public InternalClientManager internalClientManager() {
        return null;
    }

    /**
     *  Is the wrapper present?
     *  @since 0.8.8
     */
    public boolean hasWrapper() {
        return System.getProperty("wrapper.version") != null;
    }

    /**
     *  Basic mapping from service names to ports
     *  @since 0.8.12
     */
    public PortMapper portMapper() {
        return _portMapper;
    }

    /**
     * Use instead of SimpleScheduler.getInstance()
     * @since 0.9 to replace static instance in the class
     */
    public SimpleScheduler simpleScheduler() {
        if (!_simpleSchedulerInitialized)
            initializeSimpleScheduler();
        return _simpleScheduler;
    }

    private void initializeSimpleScheduler() {
        synchronized (_lock18) {
            if (_simpleScheduler == null)
                _simpleScheduler = new SimpleScheduler(this);
            _simpleSchedulerInitialized = true;
        }
    }

    /**
     * Use instead of SimpleTimer.getInstance()
     * @since 0.9 to replace static instance in the class
     * @deprecated use SimpleTimer2
     */
    public SimpleTimer simpleTimer() {
        if (!_simpleTimerInitialized)
            initializeSimpleTimer();
        return _simpleTimer;
    }

    /**
     * @deprecated use SimpleTimer2
     */
    private void initializeSimpleTimer() {
        synchronized (_lock19) {
            if (_simpleTimer == null)
                _simpleTimer = new SimpleTimer(this);
            _simpleTimerInitialized = true;
        }
    }

    /**
     * Use instead of SimpleTimer2.getInstance()
     * @since 0.9 to replace static instance in the class
     */
    public SimpleTimer2 simpleTimer2() {
        if (!_simpleTimer2Initialized)
            initializeSimpleTimer2();
        return _simpleTimer2;
    }

    private void initializeSimpleTimer2() {
        synchronized (_lock20) {
            if (_simpleTimer2 == null)
                _simpleTimer2 = new SimpleTimer2(this);
            _simpleTimer2Initialized = true;
        }
    }

    /**
     *  The controller of router, plugin, and other updates.
     *  @return always null in I2PAppContext, the update manager if in RouterContext and it is registered
     *  @since 0.9.4
     */
    public UpdateManager updateManager() {
        return null;
    }
}
