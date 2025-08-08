package fansirsqi.xposed.sesame.newutil

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

object DataStore {
    private val mapper = jacksonObjectMapper()
    private val data = ConcurrentHashMap<String, Any>()
    private val lock = ReentrantReadWriteLock()
    private lateinit var storageFile: File

    fun init(dir: File) {
        storageFile = File(dir, "DataStore.json").apply {
            if (!exists()) createNewFile()
        }
        loadFromDisk()
        startWatcher()
    }

    fun <T> get(key: String, clazz: Class<T>): T? = lock.read {
        data[key]?.let { mapper.convertValue(it, clazz) }
    }


    /* -------------------------------------------------- */
    /*  类型安全读取：Class 版（基本 / 自定义对象）         */
    /* -------------------------------------------------- */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getOrCreate(key: String, clazz: Class<T>): T = lock.write {
        data[key]?.let { return mapper.convertValue(it, clazz) }
        val default: T = when (clazz) {
            /* 基本容器 */
            java.util.List::class.java -> mutableListOf<Any>() as T
            java.util.Set::class.java -> mutableSetOf<Any>() as T
            java.util.Map::class.java -> mutableMapOf<String, Any>() as T

            /* 基本包装类型 */
            String::class.java -> "" as T
            Boolean::class.java -> false as T
            Int::class.java -> 0 as T
            Long::class.java -> 0L as T
            Double::class.java -> 0.0 as T
            Float::class.java -> 0f as T

            /* 其它：尝试无参构造 */
            else -> clazz.getDeclaredConstructor().newInstance()
        }
        data[key] = default
        saveToDisk()
        default
    }

    /* -------------------------------------------------- */
    /*  类型安全读取：TypeReference 版（支持嵌套泛型）       */
    /* -------------------------------------------------- */
    fun <T : Any> getOrCreate(key: String, typeRef: TypeReference<T>): T = lock.write {
        data[key]?.let { return mapper.convertValue(it, typeRef) }
        val default: T = createDefault(typeRef)
        data[key] = default
        saveToDisk()
        default
    }


    /* 根据 TypeReference 创建默认实例（支持嵌套） */
    @Suppress("UNCHECKED_CAST")
    private fun <T> createDefault(typeRef: TypeReference<T>): T {
        mapper.typeFactory.constructType(typeRef)
        val raw = mapper.typeFactory.constructType(typeRef).rawClass
        return when (raw) {
            java.util.List::class.java -> mutableListOf<Any>() as T
            java.util.Set::class.java -> mutableSetOf<Any>() as T
            java.util.Map::class.java -> mutableMapOf<String, Any>() as T
            else -> raw.getDeclaredConstructor().newInstance() as T
        }
    }

    private fun loadFromDisk() {
        if (storageFile.length() == 0L) return
        val loaded: Map<String, Any> = mapper.readValue(storageFile)
        data.putAll(loaded)
    }

    private fun saveToDisk() {
        storageFile.writeText(mapper.writeValueAsString(data))
    }

    private fun startWatcher() {
        Thread {
            var last = storageFile.lastModified()
            while (true) {
                Thread.sleep(1000)
                val current = storageFile.lastModified()
                if (current > last) {
                    last = current
                    loadFromDisk()
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /* -------------------------------------------------- */
    /*  简易 put / remove（可选）                          */
    /* -------------------------------------------------- */
    fun put(key: String, value: Any) = lock.write {
        data[key] = value
        saveToDisk()
    }

    fun remove(key: String) = lock.write {
        data.remove(key)
        saveToDisk()
    }
}