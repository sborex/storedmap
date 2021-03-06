/*
 * Copyright 2018 Fyodor Kravchenko {@literal(<fedd@vsetec.com>)}.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.storedmap;

import java.lang.ref.WeakReference;
import java.text.Collator;
import java.text.ParseException;
import java.text.RuleBasedCollator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import org.apache.commons.lang3.SerializationUtils;

/**
 * A named group of {@link StoredMap}s with similar structure.
 *
 * <p>
 * This class is similar to a relational database table or a key value store
 * index</p>
 *
 * <p>
 * The Category implements {@link Map} interface.</p>
 *
 * <p>
 * This class provides additional methods for retrieving metadata and StoredMaps
 * creation, retrieval and removal. They intentionally avoid the Java bean
 * getter and setter naming style for conventional use in environments that
 * treat Map values as bean properties</p>
 *
 *
 * @author Fyodor Kravchenko {@literal(<fedd@vsetec.com>)}
 */
public class Category implements Map<String, Map<String, Object>> {

    private static final RuleBasedCollator DEFAULTCOLLATOR;

    static {
        String rules = ((RuleBasedCollator) Collator.getInstance(new Locale("ru"))).getRules()
                + ((RuleBasedCollator) Collator.getInstance(Locale.US)).getRules()
                + ((RuleBasedCollator) Collator.getInstance(Locale.PRC)).getRules();
        try {
            DEFAULTCOLLATOR = new RuleBasedCollator(rules);
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
    }

    private final Store _store;
    private final Driver _driver;
    private final Object _connection;
    private final String _name;
    private final String _indexName;
    private final List<Locale> _locales = new ArrayList<>();
    private RuleBasedCollator _collator;
    private final WeakHashMap<String, WeakReference<WeakHolder>> _cache = new WeakHashMap<>();
    private final HashMap<String, Set<String>> _secondaryKeyCache = new HashMap<>();
    private final int _hash;

    private Category() {
        throw new UnsupportedOperationException();
    }

    Category(Store store, String name) {

        _name = name;
        _store = store;

        int hash = 5;
        hash = 53 * hash + Objects.hashCode(this._store);
        hash = 53 * hash + Objects.hashCode(this._name);
        _hash = hash;

        _connection = store.getConnection();
        _driver = store.getDriver();

        String indexName = store.applicationCode() + "_" + name;
        _indexName = Util.transformCategoryNameToIndexName(_driver, _store, _connection, _name, indexName);

        // get category locales
        String localesIndexStorageName = Util.getRidOfNonLatin(_store.applicationCode()) + "__locales";
        byte[] localesB = _driver.get(_indexName, localesIndexStorageName, _connection);
        Locale[] locales;
        if (localesB != null && localesB.length > 0) {
            locales = (Locale[]) SerializationUtils.deserialize(localesB);
            locales(locales);
        }
    }

    /**
     * Sets the language information that will be used for sorting.
     *
     * <p>
     * This method allows to provide one or more {@link Locale}s which will be
     * used for generating collation codes for string data, or for hinting the
     * storing mechanism about languages used in the {@link StoredMap}. The
     * {@link Driver} may use this information for indexing the stored
     * structure</p>
     *
     * <p>
     * The order of Locale objects provided does matter. The collation rules
     * take it into account and attempt to provide collation code that will
     * allow certain languages go before others, if they use different
     * alphabets</p>
     *
     * @param locales An array of {@link Locale}s to associate with this
     * Category. The order matters
     */
    public final synchronized void locales(Locale[] locales) {
        _locales.clear();
        _locales.addAll(Arrays.asList(locales));

        byte[] localesB = SerializationUtils.serialize(locales);
        String localesIndexStorageName = Util.getRidOfNonLatin(_store.applicationCode()) + "__locales";
        _driver.put(_indexName, localesIndexStorageName, _connection, localesB, () -> {
        }, () -> {
        });

        if (locales.length == 0) {
            _collator = DEFAULTCOLLATOR;
        } else {
            String rules = "";
            for (Locale locale : locales) {
                RuleBasedCollator coll = (RuleBasedCollator) Collator.getInstance(locale);
                rules = rules + coll.getRules();
            }
            try {
                _collator = new RuleBasedCollator(rules);
            } catch (ParseException e) {
                throw new RuntimeException(e);
            }
        }

        // TODO: recollate all objects in this category =)
    }

    /**
     * The language information associated with this Category
     *
     *
     * @return Locale objects
     */
    public Locale[] locales() {
        return _locales.toArray(new Locale[_locales.size()]);
    }

    public RuleBasedCollator collator() {
        return _collator;
    }

    /**
     * Returns the {@link Store} this Category belongs to
     *
     * @return The Store
     */
    public Store store() {
        return _store;
    }

    /**
     * Returns this Category's name
     *
     * @return the name of this Category
     */
    public String name() {
        return _name;
    }

    public String internalIndexName() {
        return _indexName;
    }

    void _removeFromCache(String key) {
        synchronized (_cache) {
            _cache.remove(key);
        }
    }

    Set<String> _keyCache() {
        synchronized (_cache) {
            return new HashSet(_cache.keySet());
        }
    }

    Set<String> _secondaryKeyCache(String secondaryKey) {
        if (secondaryKey != null) {
            Set<String> secs;
            synchronized (_secondaryKeyCache) {
                secs = _secondaryKeyCache.get(secondaryKey);
            }
            if (secs == null) {
                return Collections.EMPTY_SET;
            }
            synchronized (secs) {
                return new HashSet<>(secs);
            }
        } else {
            return Collections.EMPTY_SET;
        }
    }

    void _cacheSecondaryKey(String key, MapData map) {
        String secondaryKey = map.getSecondarKey();
        if (secondaryKey != null) {
            Set<String> secs;
            synchronized (_secondaryKeyCache) {
                secs = _secondaryKeyCache.get(secondaryKey);
                if (secs == null) {
                    secs = new HashSet<>();
                    _secondaryKeyCache.put(secondaryKey, secs);
                }
            }
            synchronized (secs) {
                secs.add(key);
            }
        }
    }

    void _uncacheSecondaryKey(String key, MapData map) {
        String secondaryKey = map.getSecondarKey();
        if (secondaryKey != null) {
            Set<String> secs;
            synchronized (_secondaryKeyCache) {
                secs = _secondaryKeyCache.get(secondaryKey);
                if (secs != null) {
                    synchronized (secs) {
                        secs.remove(key);
                        if (secs.isEmpty()) {
                            _secondaryKeyCache.remove(secondaryKey);
                        }
                    }
                }
            }
        }
    }

    @Override
    public int hashCode() {
        return _hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final Category other = (Category) obj;
        if (!Objects.equals(this._name, other._name)) {
            return false;
        }
        return Objects.equals(this._store, other._store);
    }

    // ***************************************************
    //             basic data manipulations
    // ***************************************************
    /**
     * The main method to get a {@link StoredMap} by it's identifier
     *
     * <p>
     * The StoredMap with the provided identifier may or may not be present in
     * the store, in the latter case it will be created on the fly.</p>
     *
     * <p>
     * The StoredMaps are identified by the key, which can be any string in any
     * language and containing any characters. The only limitation is it's
     * length. This key size is determined by the underlying storage and
     * reported by the driver's method
     * {@link Driver#getMaximumKeyLength(java.lang.Object)}</p>
     *
     * @param key the StoredMap identifier
     * @return the StoredMap, either new or previously persisted
     */
    public StoredMap map(String key) {
        if (key == null) {
            return null;
        }
        StoredMap ret;
        synchronized (_cache) {
            WeakHolder cached;
            WeakReference<WeakHolder> wr = _cache.get(key);
            if (wr != null) {
                cached = wr.get();
            } else {
                cached = null;
            }
            if (cached == null) {
                WeakHolder holder = new WeakHolder(key, this);
                ret = new StoredMap(this, holder);
                _cache.put(key, new WeakReference<>(holder));
            } else {
                ret = new StoredMap(this, cached);
            }
        }
        return ret;
    }

    /**
     * Fast {@link StoredMap} creation with the specified id
     *
     * <p>
     * The calling procedure should privide an completely new id that is not
     * present in the database, for example, a newly created UUID. </p>
     *
     * <p>
     * The procedure doesn't check for existance of a stored map with the
     * specified id and simply creates it. This allows the system to invoke the
     * underlying storage asynchronously. Until the map is not stored in the
     * actual persistent storage, it is cached by its identifier so that
     * subsequent modifications are written to the same map
     *
     * @param key a unique identifier absent from the database prior to this
     * call
     * @return the newly created StoredMap object
     */
    public StoredMap create(String key) {
        StoredMap ret = map(key);
        try {
            ret.fastCreate = true;
            return ret;
        } catch (NullPointerException e) {
            return null;
        }
    }

    /**
     * Gets an iterable of all StoredMaps in this Category.
     *
     * <p>
     * This method will include items that were just inserted (and scheduled for
     * asynchronous persist)</p>
     *
     * @return all StoredMaps of this Category
     */
    public Iterable<StoredMap> maps() {
        return new StoredMaps(this, _driver.get(_indexName, _connection));
    }

    public Iterable<String> keys() {
        return new Iterable<String>() {
            @Override
            public Iterator<String> iterator() {
                return new StoredMaps(Category.this, _driver.get(_indexName, _connection)).keyIterator();
            }
        };
    }

    /**
     * Gets the iterable collection of StoredMaps that conform with the
     * database-specific query, have a
     * {@link StoredMap#sorter(java.lang.Object)} set to a value in the
     * specified range and that are associated with any of the specified tags
     * set by {@link StoredMap#tags(java.lang.String[])}.
     *
     * <p>
     * This method may omit the most recently added StoredMaps if they weren't
     * yet persisted asynchronously</p>
     *
     * @param secondaryKey
     * @param minSorter minimum value of Sorter
     * @param maxSorter maximum value of Sorter
     * @param anyOfTags array of tag Strings
     * @param ascending false if the results should be ordered from maximum to
     * @param textQuery a database-specific query in textual form
     * @return iterable of Stored Maps
     */
    public Iterable<StoredMap> maps(String secondaryKey, Object minSorter, Object maxSorter, String[] anyOfTags, Boolean ascending, String textQuery) {
        if (anyOfTags == null || anyOfTags.length == 0) {
            anyOfTags = new String[]{MapData.NOTAGSMAGICAL};
        }
        byte[] min = Util.translateSorterIntoBytes(minSorter, _collator, _driver.getMaximumSorterLength(_connection));
        byte[] max = Util.translateSorterIntoBytes(maxSorter, _collator, _driver.getMaximumSorterLength(_connection));
        return new StoredMaps(this, _driver.get(_indexName, _connection, secondaryKey, min, max, anyOfTags, ascending, textQuery), (minSorter != null || maxSorter != null || anyOfTags != null || ascending != null || textQuery != null ? null : secondaryKey));
    }

    public Iterable<String> keys(String secondaryKey, Object minSorter, Object maxSorter, String[] anyOfTags, Boolean ascending, String textQuery) {
        final String[] anyOfTagsFinal;
        if (anyOfTags == null || anyOfTags.length == 0) {
            anyOfTagsFinal = new String[]{MapData.NOTAGSMAGICAL};
        } else {
            anyOfTagsFinal = anyOfTags;
        }
        byte[] min = Util.translateSorterIntoBytes(minSorter, _collator, _driver.getMaximumSorterLength(_connection));
        byte[] max = Util.translateSorterIntoBytes(maxSorter, _collator, _driver.getMaximumSorterLength(_connection));
        return new Iterable<String>() {
            @Override
            public Iterator<String> iterator() {
                return new StoredMaps(Category.this, _driver.get(_indexName, _connection, secondaryKey, min, max, anyOfTagsFinal, ascending, textQuery), (minSorter != null || maxSorter != null || anyOfTags != null || ascending != null || textQuery != null ? null : secondaryKey)).keyIterator();
            }
        };
    }

    /**
     * Gets the {@link List} of StoredMaps of the specified size, starting from
     * a specified item, that conform to the database-specific query, have a
     * {@link StoredMap#sorter(java.lang.Object)} set to a value in the
     * specified range and that are associated with any of the specified tags
     * set by {@link StoredMap#tags(java.lang.String[])}.
     *
     * @param secondaryKey
     * @param minSorter minimum value of Sorter
     * @param maxSorter maximum value of Sorter
     * @param anyOfTags array of tag Strings
     * @param ascending false if the results should be ordered from maximum to
     * @param textQuery a database-specific query in textual form
     * @param from the starting item
     * @param size the maximum size of the returned list
     * @return list of Stored Maps
     */
    public List<StoredMap> maps(String secondaryKey, Object minSorter, Object maxSorter, String[] anyOfTags, Boolean ascending, String textQuery, int from, int size) {
        if (anyOfTags == null || anyOfTags.length == 0) {
            anyOfTags = new String[]{MapData.NOTAGSMAGICAL};
        }
        byte[] min = Util.translateSorterIntoBytes(minSorter, _collator, _driver.getMaximumSorterLength(_connection));
        byte[] max = Util.translateSorterIntoBytes(maxSorter, _collator, _driver.getMaximumSorterLength(_connection));
        StoredMaps maps = new StoredMaps(this, _driver.get(_indexName, _connection, secondaryKey, min, max, anyOfTags, ascending, textQuery, from, size), (minSorter != null || maxSorter != null || anyOfTags != null || ascending != null || textQuery != null ? null : secondaryKey));
        ArrayList<StoredMap> ret = new ArrayList<>((int) (size > 1000 ? 1000 : size * 1.7));
        for (StoredMap map : maps) {
            ret.add(map);
        }
        return Collections.unmodifiableList(ret);
    }

    public List<String> keys(String secondaryKey, Object minSorter, Object maxSorter, String[] anyOfTags, Boolean ascending, String textQuery, int from, int size) {
        if (anyOfTags == null || anyOfTags.length == 0) {
            anyOfTags = new String[]{MapData.NOTAGSMAGICAL};
        }
        byte[] min = Util.translateSorterIntoBytes(minSorter, _collator, _driver.getMaximumSorterLength(_connection));
        byte[] max = Util.translateSorterIntoBytes(maxSorter, _collator, _driver.getMaximumSorterLength(_connection));
        Iterator<String> keys = new StoredMaps(this, _driver.get(_indexName, _connection, secondaryKey, min, max, anyOfTags, ascending, textQuery, from, size), (minSorter != null || maxSorter != null || anyOfTags != null || ascending != null || textQuery != null ? null : secondaryKey)).keyIterator();
        ArrayList<String> ret = new ArrayList<>((int) (size > 1000 ? 1000 : size * 1.7));
        while (keys.hasNext()) {
            ret.add(keys.next());
        }
        return Collections.unmodifiableList(ret);
    }

    // **********************************
    //             counts
    // **********************************
    /**
     * Counts all StoredMaps in this Category.
     *
     * <p>
     * The number may miss the StoredMaps added most recently</p>
     *
     * @return number of StoredMaps in the Category
     */
    public long count() {
        return _driver.count(_indexName, _connection);
    }

    /**
     * Counts all StoredMaps in this Category that conform with the
     * database-specific query, have a
     * {@link StoredMap#sorter(java.lang.Object)} set to a value in the
     * specified range and that are associated with any of the specified tags
     * set by {@link StoredMap#tags(java.lang.String[])}.
     *
     * <p>
     * The number may miss the StoredMaps added most recently</p>
     *
     * @param secondaryKey
     * @param minSorter minimum value of Sorter
     * @param maxSorter maximum value of Sorter
     * @param anyOfTags array of tag Strings
     * @param textQuery a database-specific query in textual form
     * @return number of StoredMaps in the Category
     */
    public long count(String secondaryKey, Object minSorter, Object maxSorter, String[] anyOfTags, String textQuery) {
        if (anyOfTags == null || anyOfTags.length == 0) {
            anyOfTags = new String[]{MapData.NOTAGSMAGICAL};
        }
        byte[] min = Util.translateSorterIntoBytes(minSorter, _collator, _driver.getMaximumSorterLength(_connection));
        byte[] max = Util.translateSorterIntoBytes(maxSorter, _collator, _driver.getMaximumSorterLength(_connection));
        return _driver.count(_indexName, _connection, secondaryKey, min, max, anyOfTags, textQuery);
    }

    // **********************************
    //              map api
    // **********************************
    @Override
    public int size() {
        long count = count();
        if (count > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        } else {
            return (int) count;
        }
    }

    @Override
    public boolean isEmpty() {
        return size() <= 0;
    }

    @Override
    public boolean containsKey(Object key) {
        if (key instanceof String) {
            return _driver.get((String) key, _indexName, _connection) != null;
        } else {
            return false;
        }
    }

    @Override
    public boolean containsValue(Object value) {
        if (value instanceof StoredMap) {
            return ((StoredMap) value).category().equals(Category.this);
        } else {
            return false;
        }
    }

    @Override
    public Map<String, Object> get(Object key) {
        if (key instanceof String) {
            StoredMap sm = map((String) key);
            if (sm.isEmpty()) {
                return null;
            } else {
                return sm;
            }
        } else {
            return null;
        }
    }

    @Override
    public synchronized Map<String, Object> put(String key, Map<String, Object> value) {
        StoredMap sm = map(key);
        if (sm == null) {
            return null;
        }
        Map<String, Object> ret;
        if (!sm.isEmpty()) {
            ret = Collections.unmodifiableMap(new HashMap<>(sm));
            sm.clear();
        } else {
            ret = null;
        }
        sm.putAll(value);
        return ret;
    }

    @Override
    public synchronized Map<String, Object> remove(Object key) {
        if (key instanceof String) {
            StoredMap sm = map((String) key);
            Map<String, Object> ret;
            if (!sm.isEmpty()) {
                ret = Collections.unmodifiableMap(new HashMap<>(sm));
            } else {
                ret = null;
            }
            sm.remove();
            return ret;
        } else {
            return null;
        }

    }

    @Override
    public void putAll(Map<? extends String, ? extends Map<String, Object>> m) {
        for (Entry<? extends String, ? extends Map<String, Object>> entry : m.entrySet()) {
            put(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public synchronized void clear() {
        _driver.removeAll(_indexName, _connection);
    }

    @Override
    public Set<String> keySet() {
        return new Set<String>() {
            @Override
            public int size() {
                return Category.this.size();
            }

            @Override
            public boolean isEmpty() {
                return Category.this.isEmpty();
            }

            @Override
            public boolean contains(Object o) {
                return Category.this.containsKey(o);
            }

            @Override
            public Iterator<String> iterator() {

                return Category.this.keys().iterator();

            }

            @Override
            public Object[] toArray() {
                ArrayList ret = new ArrayList();
                Iterator<String> i = iterator();
                while (i.hasNext()) {
                    ret.add(i.next());
                }
                return ret.toArray();
            }

            @Override
            public <T> T[] toArray(T[] arg0) {
                ArrayList<T> ret = new ArrayList<>();
                Iterator<String> i = iterator();
                while (i.hasNext()) {
                    ret.add((T) i.next());
                }
                return ret.toArray(arg0);
            }

            @Override
            public boolean add(String e) {
                return true;
            }

            @Override
            public boolean remove(Object o) {
                return Category.this.remove(o) != null;
            }

            @Override
            public boolean containsAll(Collection<?> c) {
                for (Object o : c) {
                    if (!Category.this.containsKey(o)) {
                        return false;
                    }
                }
                return true;
            }

            @Override
            public boolean addAll(Collection<? extends String> c) {
                return true;
            }

            @Override
            public boolean retainAll(Collection<?> c) {

                String[] currentKeys = toArray(new String[0]);
                boolean ret = false;
                for (String key : currentKeys) {
                    if (!c.contains(key)) {
                        Category.this.remove(key);
                        ret = true;
                    }
                }
                return ret;
            }

            @Override
            public boolean removeAll(Collection<?> c) {
                boolean ret = false;
                for (Object o : c) {
                    if (Category.this.remove(o) != null) {
                        ret = true;
                    }
                }
                return ret;
            }

            @Override
            public void clear() {
                Category.this.clear();
            }
        };
    }

    @Override
    public Collection<Map<String, Object>> values() {
        return new Collection<Map<String, Object>>() {
            @Override
            public int size() {
                return Category.this.size();
            }

            @Override
            public boolean isEmpty() {
                return Category.this.isEmpty();
            }

            @Override
            public boolean contains(Object o) {
                return Category.this.containsValue(o);
            }

            @Override
            public Iterator<Map<String, Object>> iterator() {
                Iterator<StoredMap> i = Category.this.maps().iterator();

                return new Iterator<Map<String, Object>>() {

                    @Override
                    public boolean hasNext() {
                        return i.hasNext();
                    }

                    @Override
                    public Map<String, Object> next() {
                        return i.next();
                    }
                };
            }

            @Override
            public Object[] toArray() {
                ArrayList ret = new ArrayList();
                Iterator<Map<String, Object>> i = iterator();
                while (i.hasNext()) {
                    ret.add(i.next());
                }
                return ret.toArray();
            }

            @Override
            public <T> T[] toArray(T[] arg0) {
                ArrayList<T> ret = new ArrayList<>();
                Iterator<Map<String, Object>> i = iterator();
                while (i.hasNext()) {
                    ret.add((T) i.next());
                }
                return ret.toArray(arg0);
            }

            @Override
            public boolean add(Map<String, Object> e) {
                throw new UnsupportedOperationException("Adding a map to category without any key is not supported.");
            }

            @Override
            public boolean remove(Object o) {
                boolean ret = false;
                if (o instanceof StoredMap) {
                    StoredMap sm = (StoredMap) o;
                    if (sm.category().equals(Category.this)) {
                        sm.remove();
                        ret = true;
                    }
                }
                return ret;
            }

            @Override
            public boolean containsAll(Collection<?> c) {
                for (Object o : c) {
                    if (!Category.this.containsValue(o)) {
                        return false;
                    }
                }
                return true;
            }

            @Override
            public boolean addAll(Collection<? extends Map<String, Object>> c) {
                throw new UnsupportedOperationException("Adding maps to category without keys is not supported.");
            }

            @Override
            public boolean removeAll(Collection<?> c) {
                boolean ret = false;
                for (Object o : c) {
                    if (o instanceof StoredMap) {
                        StoredMap sm = (StoredMap) o;
                        if (sm.category().equals(Category.this)) {
                            sm.remove();
                            ret = true;
                        }
                    }
                }
                return ret;
            }

            @Override
            public boolean retainAll(Collection<?> c) {
                HashSet<String> keysToRetain = new HashSet<>();
                for (Object o : c) {
                    if (!(o instanceof StoredMap)) {
                        return false;
                    } else {
                        keysToRetain.add(((StoredMap) o).key());
                    }
                }
                StoredMap[] currentValues = toArray(new StoredMap[0]);
                boolean ret = false;
                for (StoredMap map : currentValues) {
                    if (!keysToRetain.contains(map.key())) {
                        map.remove();
                        ret = true;
                    }
                }
                return ret;
            }

            @Override
            public void clear() {
                Category.this.clear();
            }
        };
    }

    @Override
    public Set<Entry<String, Map<String, Object>>> entrySet() {
        return new Set<Entry<String, Map<String, Object>>>() {
            @Override
            public int size() {
                return Category.this.size();
            }

            @Override
            public boolean isEmpty() {
                return Category.this.isEmpty();
            }

            @Override
            public boolean contains(Object o) {
                if (o instanceof Entry) {
                    Entry e = (Entry) o;
                    Object v = e.getValue();
                    if (v instanceof StoredMap) {
                        StoredMap sm = (StoredMap) v;
                        if (sm.category().equals(Category.this)) {
                            return sm.key().equals(e.getKey());
                        } else {
                            return false;
                        }
                    } else {
                        return false;
                    }
                } else {
                    return false;
                }
            }

            @Override
            public Iterator<Entry<String, Map<String, Object>>> iterator() {

                Iterator<Map<String, Object>> i = Category.this.values().iterator();

                return new Iterator<Entry<String, Map<String, Object>>>() {
                    @Override
                    public boolean hasNext() {
                        return i.hasNext();
                    }

                    @Override
                    public Entry<String, Map<String, Object>> next() {

                        StoredMap sm = (StoredMap) i.next();

                        return new Entry<String, Map<String, Object>>() {
                            @Override
                            public String getKey() {
                                return sm.key();
                            }

                            @Override
                            public Map<String, Object> getValue() {
                                return sm;
                            }

                            @Override
                            public Map<String, Object> setValue(Map<String, Object> value) {
                                return Category.this.put(sm.key(), value);
                            }
                        };
                    }
                };
            }

            @Override
            public Object[] toArray() {
                ArrayList ret = new ArrayList();
                for (Entry<String, Map<String, Object>> entry : entrySet()) {
                    ret.add(entry);
                }
                return ret.toArray();
            }

            @Override
            public <T> T[] toArray(T[] arg0) {
                ArrayList<T> ret = new ArrayList<>();
                for (Entry<String, Map<String, Object>> entry : entrySet()) {
                    ret.add((T) entry);
                }
                return ret.toArray(arg0);
            }

            @Override
            public boolean add(Entry<String, Map<String, Object>> e) {
                Category.this.put(e.getKey(), e.getValue());
                return true;
            }

            @Override
            public boolean remove(Object o) {
                if (o instanceof Entry) {
                    Category.this.remove(((Entry) o).getKey());
                    return true;
                }
                return false;
            }

            @Override
            public boolean containsAll(Collection<?> c) {
                for (Object o : c) {
                    if (!Category.this.values().contains(o)) {
                        return false;
                    }
                }
                return true;
            }

            @Override
            public boolean addAll(Collection<? extends Entry<String, Map<String, Object>>> c) {
                boolean ret = false;
                for (Entry e : c) {
                    if (add(e)) {
                        ret = true;
                    }
                }
                return ret;
            }

            @Override
            public boolean retainAll(Collection<?> c) {
                throw new UnsupportedOperationException("Not supported yet.");
            }

            @Override
            public boolean removeAll(Collection<?> c) {
                boolean ret = false;
                for (Object o : c) {
                    if (o instanceof Entry) {
                        Entry e = (Entry) o;
                        if (Category.this.remove(e.getKey()) != null) {
                            ret = true;
                        }
                    }
                }
                return ret;
            }

            @Override
            public void clear() {
                Category.this.clear();
            }
        };
    }

}
