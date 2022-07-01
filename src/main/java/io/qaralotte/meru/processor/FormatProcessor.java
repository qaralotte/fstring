package io.qaralotte.meru.processor;

import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeTranslator;
import com.sun.tools.javac.util.List;
import io.qaralotte.meru.annotation.EnableFormat;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SupportedAnnotationTypes("io.qaralotte.meru.annotation.EnableFormat")
@SupportedSourceVersion(SourceVersion.RELEASE_8)
public class FormatProcessor extends BaseProcessor {

    /**
     * 格式化字符串
     * @param lit 需要格式化的字符串，其中 {} 内的需要处理成表达式
     * @return 生成正确的表达式
     */
    private JCTree.JCExpression formatStringLiteral(String lit) {
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
        if (lastEnd < lit.length()) {
            lastAddExpression = treeMaker.Binary(
                    JCTree.Tag.PLUS,
                    lastAddExpression,
                    treeMaker.Literal(lit.substring(lastEnd))
            );
        }

        return lastAddExpression;
    }

    private JCTree.JCExpression handleArg(JCTree.JCExpression arg) {
        switch (arg.getKind()) {
            case PLUS:
                // 众所周知，两个字符串字面量相加一定会被javac整合在一起变成一个字面量
                // 所以出现在这里的一定是(变量 or 表达式) + (字面量 or 变量 or 表达式)
                //       root
                //      /    \
                //     l      r
                //    / \
                //   l   r

                JCTree.JCBinary jcBinary = (JCTree.JCBinary) arg;
                return treeMaker.Binary(
                        JCTree.Tag.PLUS,
                        handleArg(jcBinary.lhs),
                        handleArg(jcBinary.rhs)
                );
            case STRING_LITERAL:
                // 字面量
                JCTree.JCLiteral jcLiteral = (JCTree.JCLiteral) arg;
                return formatStringLiteral(jcLiteral.value.toString());
            case IDENTIFIER:
                // 变量
            case METHOD_INVOCATION:
                // 调用方法
                return arg;
            default:
                messager.printMessage(Diagnostic.Kind.ERROR, "不支持的类型: " + arg.getKind());
                return null;
        }
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
                        JCTree.JCExpression argExpr = methodInvocation.args.get(0);
                        methodInvocation.args = List.of(handleArg(argExpr));
                    }

                    super.visitApply(methodInvocation);
                }
            });
        }

        return false;
    }

}
