import 'package:flutter_test/flutter_test.dart';

import 'package:paintsprout/main.dart';

void main() {
  testWidgets('App builds and shows the tool rail', (tester) async {
    await tester.pumpWidget(const PaintsproutApp());
    // The pencil tool button and Save action are present in the rail.
    expect(find.byTooltip('Pencil'), findsOneWidget);
    expect(find.byTooltip('Save PNG'), findsOneWidget);
  });
}
