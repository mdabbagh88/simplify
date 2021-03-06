package org.cf.smalivm.context;

import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.cf.smalivm.VirtualMachine;
import org.cf.smalivm.smali.ClassManager;
import org.cf.util.ClassNameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rits.cloning.Cloner;
import com.rits.cloning.ObjenesisInstantiationStrategy;

public class ClonerFactory {

    private static final Logger log = LoggerFactory.getLogger(ClonerFactory.class.getSimpleName());

    private static final Map<ClassManager, Cloner> cache = new WeakHashMap<ClassManager, Cloner>();

    /**
     * This builds a fresh cloner. This is necessary because Cloner does some caching of classes which is a problem
     * because classes are dynamically generated. If multiple virtual machines are used, any classes of instances that
     * were cloned in the first virtual machine will be cached. The second virtual machine will have a different class
     * loader and will dynamically generate different classes.
     * 
     * The reason there is some ClassManager related caching is to speed up tests, i.e. to prevent having to read
     * configuration, create classes, and create a new cloner for every test.
     * 
     * @param vm
     * @return
     */
    public static Cloner build(VirtualMachine vm) {
        ClassManager classManager = vm.getClassManager();
        Cloner cloner = cache.get(classManager);
        if (cloner != null) {
            return cloner;
        }

        Set<String> immutableClasses = vm.getConfiguration().getImmutableClasses();
        ClassLoader classLoader = vm.getClassLoader();

        cloner = new Cloner(new ObjenesisInstantiationStrategy());
        for (String immutableClass : immutableClasses) {
            if (immutableClass.length() <= 1 || immutableClass.contains("$") || immutableClass.startsWith("Ljava/")) {
                /*
                 * Creating a cloner is expensive because it has to build and load classes. Don't bother with:
                 * primitives
                 * inner classes
                 * protected packages
                 * To keep things fast and avoid warnings.
                 */
                continue;
            }

            String binaryName = ClassNameUtils.internalToBinary(immutableClass);
            Class<?> klazz;
            try {
                klazz = classLoader.loadClass(binaryName);
            } catch (ClassNotFoundException e) {
                if (log.isErrorEnabled()) {
                    log.error("Unable to load immutable class (not found): {}", immutableClass);
                }
                continue;
            }
            cloner.dontClone(klazz);
        }
        cache.put(classManager, cloner);

        return cloner;
    }

}
