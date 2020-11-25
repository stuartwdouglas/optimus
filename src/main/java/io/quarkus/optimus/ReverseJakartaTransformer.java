package io.quarkus.optimus;

import org.eclipse.transformer.Transformer;
import org.eclipse.transformer.Transformer.AppOption;

import java.util.HashMap;
import java.util.Map;

public class ReverseJakartaTransformer {

	public static void main(String[] args) throws Exception {
		Transformer jTrans = new Transformer(System.out, System.err);
		jTrans.setOptionDefaults(ReverseJakartaTransformer.class, getOptionDefaults());
		jTrans.setArgs(args);

		@SuppressWarnings("unused")
		int rc = jTrans.run();
		// System.exit(rc); // TODO: How should this code be returned?
	}

	public static final String	DEFAULT_RENAMES_REFERENCE		= "org/eclipse/transformer/jakarta/jakarta-renames.properties";
	public static final String	DEFAULT_VERSIONS_REFERENCE		= "org/eclipse/transformer/jakarta/jakarta-versions.properties";
	public static final String	DEFAULT_BUNDLES_REFERENCE		= "org/eclipse/transformer/jakarta/jakarta-bundles.properties";
	public static final String	DEFAULT_DIRECT_REFERENCE		= "org/eclipse/transformer/jakarta/jakarta-direct.properties";
	public static final String	DEFAULT_MASTER_TEXT_REFERENCE	= "org/eclipse/transformer/jakarta/jakarta-text-master.properties";
	public static final String	DEFAULT_PER_CLASS_CONSTANT_MASTER_REFERENCE	= "org/eclipse/transformer/jakarta/jakarta-per-class-constant-master.properties";

	public static Map<AppOption, String> getOptionDefaults() {
		HashMap<AppOption, String> optionDefaults = new HashMap<>();

		optionDefaults.put(AppOption.RULES_RENAMES, DEFAULT_RENAMES_REFERENCE);
		optionDefaults.put(AppOption.RULES_VERSIONS, DEFAULT_VERSIONS_REFERENCE);
		optionDefaults.put(AppOption.RULES_BUNDLES, DEFAULT_BUNDLES_REFERENCE);
		optionDefaults.put(AppOption.RULES_DIRECT, DEFAULT_DIRECT_REFERENCE);
		optionDefaults.put(AppOption.RULES_MASTER_TEXT, DEFAULT_MASTER_TEXT_REFERENCE);
		optionDefaults.put(AppOption.RULES_PER_CLASS_CONSTANT, DEFAULT_PER_CLASS_CONSTANT_MASTER_REFERENCE);

		return optionDefaults;
	}
}
