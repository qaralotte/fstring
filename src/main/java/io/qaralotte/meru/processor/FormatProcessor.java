package io.qaralotte.meru.processor;

import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeTranslator;
import com.sun.tools.javac.util.*;
import io.qaralotte.meru.annotation.EnableFormat;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SupportedAnnotationTypes("io.qaralotte.meru.annotation.EnableFormat")
@SupportedSourceVersion(SourceVersion.RELEASE_8)
public class FormatProcessor extends BaseProcessor {

    private JCTree.JCExpression format(String lit) {
        // 找出所有 {} 之内的字符串
        Matcher matcher = Pattern.compile("\\{.*?}").matcher(lit);
        int lastEnd = 0;
        JCTree.JCBinary lastAddExpression = null;

        // 如果匹配到结果，则开始处理格式化
        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();

            // 上一个 end 到这一次 start 之间的字符串正常处理
            JCTree.JCLiteral lastLiteral = treeMaker.Literal(lit.substring(lastEnd, start));
            lastEnd = end;

            // 开始处理格式化变量
            JCTree.JCIdent ident = treeMaker.Ident(names.fromString(lit.substring(start + 1, end - 1)));

            // 将字符串与变量相加
            JCTree.JCBinary binary = treeMaker.Binary(
                    JCTree.Tag.PLUS,
                    lastLiteral,
                    ident
            );

            if (lastAddExpression == null) {
                // 如果之前并没有任何相加语句，则这个是第一次相加
                lastAddExpression = binary;
            } else {
                // 如果之前有相加语句，则与之前的语句进行相加
                lastAddExpression = treeMaker.Binary(
                        JCTree.Tag.PLUS,
                        lastAddExpression,
                        binary
                );
            }
        }

        // 在最后可能还有字符串，需要再添加进来
        lastAddExpression = treeMaker.Binary(
                JCTree.Tag.PLUS,
                lastAddExpression,
                treeMaker.Literal(lit.substring(lastEnd))
        );

        return lastAddExpression;
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {

        // 处理有 @EnableFormat 注解的元素
        for (Element element : roundEnv.getElementsAnnotatedWith(EnableFormat.class)) {
            JCTree jcTree = trees.getTree(element);
            jcTree.accept(new TreeTranslator() {

                @Override
                public void visitApply(JCTree.JCMethodInvocation methodInvocation) {

                    JCTree.JCExpression select = methodInvocation.getMethodSelect();
                    if ("io.qaralotte.meru.utils.StringUtils.format".equals(select.toString()) ||
                        "StringUtils.format".equals(select.toString())) {

                        // 只有一个参数，而且必须是 String 类型
                        final JCTree.JCExpression[] expression = {null};
                        methodInvocation.args.get(0).accept(new TreeTranslator() {
                            @Override
                            public void visitLiteral(JCTree.JCLiteral jcLiteral) {

                                expression[0] = format(jcLiteral.value.toString());

                                super.visitLiteral(jcLiteral);
                            }
                        });

                        methodInvocation.args = List.of(expression[0]);
                    }

                    super.visitApply(methodInvocation);
                }
            });
        }

        return false;
    }

}
